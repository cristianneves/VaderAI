import {
  PROTOCOL_VERSION,
  serverMessageSchema,
  type ClientMessage,
  type Screenshot,
  type ServerMessage,
} from '@vaderai/protocol';

export type ConnectionState = 'idle' | 'connecting' | 'ready' | 'reconnecting' | 'error';

export interface SessionCallbacks {
  onMessage: (message: ServerMessage) => void;
  onState: (state: ConnectionState, detail?: string) => void;
  /**
   * Asked for once per handshake rather than captured at connect time. Access
   * tokens expire mid-session, and a reconnect carrying the token from an hour
   * ago is rejected forever. `null` means there is no session left to resume.
   */
  token: () => Promise<string | null>;
}

const RECONNECT_BASE_MS = 500;
/** Retrying is unbounded, so the delay needs a ceiling of its own. */
const MAX_BACKOFF_MS = 30_000;
/** ±20%, so a server restart does not bring every client back in the same instant. */
const JITTER = 0.2;
const HEARTBEAT_MS = 15_000;
const PONG_TIMEOUT_MS = 10_000;

/**
 * The client half of the session protocol: JSON control up, audio frames up as
 * binary, transcript and answers down.
 */
export class SessionSocket {
  private socket: WebSocket | null = null;
  private attempt = 0;
  private ready = false;
  private stopped = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private pongDeadline: ReturnType<typeof setTimeout> | null = null;
  private dropped = 0;

  constructor(
    private readonly url: string,
    private readonly callbacks: SessionCallbacks,
    private readonly open: (url: string) => WebSocket = (url) => new WebSocket(url),
    private readonly random: () => number = Math.random,
  ) {}

  connect(): void {
    this.stopped = false;
    this.attempt = 0;
    this.dropped = 0;
    this.openSocket();
  }

  private openSocket(): void {
    this.ready = false;
    // Only the first attempt announces itself here. Every later one was already
    // announced by onClose, the moment the gap opened.
    if (this.attempt === 0) this.callbacks.onState('connecting');

    const socket = this.open(this.url);
    socket.binaryType = 'arraybuffer';
    this.socket = socket;

    socket.onopen = () => {
      // The handshake cannot carry an Authorization header, so the token goes
      // in the first frame. The server closes 1008 if none arrives within 5s,
      // which is the budget this await spends — a Supabase refresh round trip
      // is well inside it, and a cached token resolves immediately.
      void this.callbacks.token().then((token) => {
        // A reconnect may have replaced the socket while we were waiting.
        if (this.socket !== socket) return;
        if (token === null) {
          // Reported as the frame the server would have sent, so the UI has
          // one path for "you are signed out" rather than two.
          const message = 'signed out — sign in again to continue';
          this.callbacks.onMessage({ type: 'error', code: 'unauthorized', message });
          this.giveUp(message);
          return;
        }
        socket.send(
          JSON.stringify({
            type: 'hello',
            protocolVersion: PROTOCOL_VERSION,
            accessToken: token,
          }),
        );
      });
    };
    socket.onmessage = (event: MessageEvent<unknown>) => this.receive(event.data);
    socket.onclose = () => this.onClose();
    // onerror is deliberately left unhandled: the spec fires onclose after it
    // every time, and onClose is the one place that decides what comes next.
    // Reporting 'error' here made a retry that was still pending look terminal.
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
      // Reconnecting cannot fix that, so this one stays terminal.
      this.callbacks.onState('error', 'unknown message from server');
      return;
    }

    if (message.data.type === 'pong') {
      // Heartbeat plumbing. The UI has no use for it, so it stops here.
      this.clearPongDeadline();
      return;
    }

    if (message.data.type === 'error' && message.data.code === 'unauthorized') {
      // Every handshake asks for a fresh token, so this is the *current*
      // credential being refused. Retrying re-sends the same rejected token
      // every 30s forever; only signing in again can fix it.
      this.callbacks.onMessage(message.data);
      this.giveUp(message.data.message);
      return;
    }

    if (message.data.type === 'ready') {
      this.ready = true;
      this.attempt = 0;
      this.dropped = 0;
      this.startHeartbeat();
      this.callbacks.onState('ready');
    }
    this.callbacks.onMessage(message.data);
  }

  /** Stops for good. The reconnect loop cannot resolve what went wrong. */
  private giveUp(detail: string): void {
    this.close();
    this.callbacks.onState('error', detail);
  }

  private onClose(): void {
    this.ready = false;
    this.socket = null;
    this.stopHeartbeat();
    if (this.stopped) {
      this.callbacks.onState('idle');
      return;
    }
    this.attempt += 1;
    // Reported now rather than when the retry fires: the user should see the
    // gap the moment it opens, not after a backoff that can reach 30s.
    this.callbacks.onState('reconnecting', `reconnecting… (attempt ${this.attempt})`);
    this.timer = setTimeout(() => this.openSocket(), this.backoff());
  }

  /** Exponential from the first retry, capped, then jittered. */
  private backoff(): number {
    const base = Math.min(RECONNECT_BASE_MS * 2 ** (this.attempt - 1), MAX_BACKOFF_MS);
    return Math.round(base * (1 + JITTER * (this.random() * 2 - 1)));
  }

  /**
   * A dropped Wi-Fi or a slept laptop leaves a socket the OS still calls open,
   * on which nothing will ever arrive again. The server answers `ping` with
   * `pong`; silence past the deadline means the connection is gone.
   */
  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeat = setInterval(() => this.beat(), HEARTBEAT_MS);
  }

  private beat(): void {
    if (this.pongDeadline !== null) return;
    this.sendJson({ type: 'ping' });
    this.pongDeadline = setTimeout(() => {
      this.pongDeadline = null;
      // Closing by hand is what puts us on the reconnect path; waiting for the
      // OS to time the socket out takes minutes.
      this.socket?.close();
    }, PONG_TIMEOUT_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeat !== null) clearInterval(this.heartbeat);
    this.heartbeat = null;
    this.clearPongDeadline();
  }

  private clearPongDeadline(): void {
    if (this.pongDeadline !== null) clearTimeout(this.pongDeadline);
    this.pongDeadline = null;
  }

  /** Skips the rest of the backoff. Nothing to do when no retry is pending. */
  retryNow(): void {
    if (this.stopped || this.timer === null) return;
    clearTimeout(this.timer);
    this.timer = null;
    this.openSocket();
  }

  /** Frames sent before the server is ready are dropped, not queued. */
  sendAudio(frame: Int16Array): void {
    if (!this.ready || this.socket === null) {
      this.dropped += 1;
      return;
    }
    this.socket.send(frame.buffer as ArrayBuffer);
  }

  /**
   * Frames dropped since the connection last came up. What the reconnect notice
   * turns into "N seconds not transcribed" — a silent gap is worse than a
   * measured one.
   */
  get droppedFrames(): number {
    return this.dropped;
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

  askAboutScreen(shot: Pick<Screenshot, 'mimeType' | 'dataBase64'>, note?: string): void {
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
    this.stopHeartbeat();
    const dead = this.socket;
    this.socket = null;
    this.ready = false;
    if (dead === null) return;
    // Detached before closing: onClose decides what state to report next, and
    // a close we asked for has nothing left to decide. Without this, giveUp's
    // 'error' would be overwritten by the 'idle' that follows.
    dead.onclose = null;
    dead.close();
  }
}
