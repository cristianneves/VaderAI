import {
  PROTOCOL_VERSION,
  serverMessageSchema,
  type ClientMessage,
  type ServerMessage,
} from '@vaderai/protocol';

export type ConnectionState = 'idle' | 'connecting' | 'ready' | 'reconnecting' | 'error';

export interface SessionCallbacks {
  onMessage: (message: ServerMessage) => void;
  onState: (state: ConnectionState, detail?: string) => void;
}

const RECONNECT_BASE_MS = 500;
const MAX_RECONNECTS = 5;

/**
 * The client half of the session protocol: JSON control up, audio frames up as
 * binary, transcript and answers down.
 */
export class SessionSocket {
  private socket: WebSocket | null = null;
  private token: string | null = null;
  private attempt = 0;
  private ready = false;
  private stopped = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly url: string,
    private readonly callbacks: SessionCallbacks,
    private readonly open: (url: string) => WebSocket = (url) => new WebSocket(url),
  ) {}

  connect(accessToken: string): void {
    this.token = accessToken;
    this.stopped = false;
    this.attempt = 0;
    this.openSocket();
  }

  private openSocket(): void {
    this.ready = false;
    this.callbacks.onState(this.attempt === 0 ? 'connecting' : 'reconnecting');

    const socket = this.open(this.url);
    socket.binaryType = 'arraybuffer';
    this.socket = socket;

    socket.onopen = () => {
      // The handshake cannot carry an Authorization header, so the token goes
      // in the first frame. The server closes 1008 if it does not arrive.
      socket.send(
        JSON.stringify({
          type: 'hello',
          protocolVersion: PROTOCOL_VERSION,
          accessToken: this.token,
        }),
      );
    };
    socket.onmessage = (event: MessageEvent<unknown>) => this.receive(event.data);
    socket.onclose = () => this.onClose();
    socket.onerror = () => this.callbacks.onState('error', 'connection failed');
  }

  private receive(data: unknown): void {
    if (typeof data !== 'string') return;

    let parsed: unknown;
    try {
      parsed = JSON.parse(data);
    } catch {
      this.callbacks.onState('error', 'unreadable message from server');
      return;
    }

    const message = serverMessageSchema.safeParse(parsed);
    if (!message.success) {
      // A message we cannot parse means the two protocol halves have drifted.
      this.callbacks.onState('error', 'unknown message from server');
      return;
    }

    if (message.data.type === 'ready') {
      this.ready = true;
      this.attempt = 0;
      this.callbacks.onState('ready');
    }
    this.callbacks.onMessage(message.data);
  }

  private onClose(): void {
    this.ready = false;
    this.socket = null;
    if (this.stopped) {
      this.callbacks.onState('idle');
      return;
    }
    if (this.attempt >= MAX_RECONNECTS) {
      this.callbacks.onState('error', `gave up after ${MAX_RECONNECTS} reconnect attempts`);
      return;
    }
    const delay = RECONNECT_BASE_MS * 2 ** this.attempt;
    this.attempt += 1;
    this.timer = setTimeout(() => this.openSocket(), delay);
  }

  /** Frames sent before the server is ready are dropped, not queued. */
  sendAudio(frame: Int16Array): void {
    if (!this.ready || this.socket === null) return;
    this.socket.send(frame.buffer as ArrayBuffer);
  }

  /**
   * Asks for an answer. With no question the server answers the interviewer's
   * most recent turn, which is what the bare Ctrl+Enter does; with one it
   * answers that instead, and can see the answers it already gave.
   */
  ask(question?: string): void {
    const trimmed = question?.trim();
    this.sendJson(
      trimmed === undefined || trimmed === ''
        ? { type: 'ask', trigger: 'manual' }
        : { type: 'ask', trigger: 'manual', question: trimmed },
    );
  }

  askAboutScreen(shot: { mimeType: 'image/png'; dataBase64: string }, note?: string): void {
    const trimmed = note?.trim();
    this.sendJson(
      trimmed === undefined || trimmed === ''
        ? { type: 'screenshot', ...shot }
        : { type: 'screenshot', ...shot, note: trimmed },
    );
  }

  private sendJson(message: ClientMessage): void {
    if (!this.ready || this.socket === null) return;
    this.socket.send(JSON.stringify(message));
  }

  close(): void {
    this.stopped = true;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    this.socket?.close();
    this.socket = null;
    this.ready = false;
  }
}
