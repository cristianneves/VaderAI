import { PROTOCOL_VERSION, type ServerMessage } from '@vaderai/protocol';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionSocket, type ConnectionState } from './session';

const SESSION_ID = '6f1c9a2e-7b3d-4c8e-9f10-2a5b6c7d8e90';

/** Just enough WebSocket to drive the client without a server. */
class FakeSocket {
  static instances: FakeSocket[] = [];
  binaryType = 'blob';
  readonly sent: unknown[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;

  constructor() {
    FakeSocket.instances.push(this);
  }

  send(payload: unknown): void {
    this.sent.push(payload);
  }

  close(): void {
    this.closed = true;
  }

  open(): void {
    this.onopen?.();
  }

  receive(message: unknown): void {
    this.onmessage?.({ data: JSON.stringify(message) } as MessageEvent<unknown>);
  }

  receiveRaw(data: unknown): void {
    this.onmessage?.({ data } as MessageEvent<unknown>);
  }

  drop(): void {
    this.onclose?.();
  }
}

describe('SessionSocket', () => {
  let messages: ServerMessage[];
  let states: { state: ConnectionState; detail?: string }[];

  /** 0.5 lands the jitter dead centre, so backoff delays stay exact in tests. */
  const build = (random = (): number => 0.5): SessionSocket =>
    new SessionSocket(
      'ws://localhost:8787/v1/session',
      {
        onMessage: (message) => messages.push(message),
        onState: (state, detail) => states.push({ state, detail }),
      },
      () => new FakeSocket() as unknown as WebSocket,
      random,
    );

  const ready = (socket: FakeSocket): void =>
    socket.receive({ type: 'ready', sessionId: SESSION_ID });

  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.instances = [];
    messages = [];
    states = [];
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('sends hello as its very first frame', () => {
    build().connect('token-abc');
    const socket = FakeSocket.instances[0]!;

    socket.open();

    expect(socket.sent).toHaveLength(1);
    expect(JSON.parse(socket.sent[0] as string)).toEqual({
      type: 'hello',
      protocolVersion: PROTOCOL_VERSION,
      accessToken: 'token-abc',
    });
  });

  it('reports ready and forwards server messages', () => {
    build().connect('token-abc');
    const socket = FakeSocket.instances[0]!;
    socket.open();

    ready(socket);
    socket.receive({ type: 'transcript', channel: 0, text: 'hello there', isFinal: true });

    expect(states.map((s) => s.state)).toContain('ready');
    expect(messages).toHaveLength(2);
    expect(messages[1]).toEqual({
      type: 'transcript',
      channel: 0,
      text: 'hello there',
      isFinal: true,
    });
  });

  it('requests binary frames as ArrayBuffer', () => {
    build().connect('token-abc');

    expect(FakeSocket.instances[0]!.binaryType).toBe('arraybuffer');
  });

  it('drops audio until the server is ready', () => {
    const session = build();
    session.connect('token-abc');
    const socket = FakeSocket.instances[0]!;
    socket.open();

    session.sendAudio(new Int16Array(3200));

    expect(socket.sent).toHaveLength(1); // hello only
  });

  it('sends audio once ready', () => {
    const session = build();
    session.connect('token-abc');
    const socket = FakeSocket.instances[0]!;
    socket.open();
    ready(socket);

    session.sendAudio(new Int16Array(3200));

    expect(socket.sent).toHaveLength(2);
    expect(socket.sent[1]).toBeInstanceOf(ArrayBuffer);
    expect((socket.sent[1] as ArrayBuffer).byteLength).toBe(6400);
  });

  /** Connects, opens and readies a session, returning both halves. */
  const live = (): { session: SessionSocket; socket: FakeSocket } => {
    const session = build();
    session.connect('token-abc');
    const socket = FakeSocket.instances[0]!;
    socket.open();
    ready(socket);
    return { session, socket };
  };

  it('asks with no question, which answers the interviewer instead', () => {
    const { session, socket } = live();

    session.ask();

    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({ type: 'ask', trigger: 'manual' });
  });

  it('carries a typed question', () => {
    const { session, socket } = live();

    session.ask('Explain that more simply.');

    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({
      type: 'ask',
      trigger: 'manual',
      question: 'Explain that more simply.',
    });
  });

  it('trims a question and omits one that was only whitespace', () => {
    const { session, socket } = live();

    session.ask('  padded  ');
    expect(JSON.parse(socket.sent.at(-1) as string).question).toBe('padded');

    session.ask('   ');
    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({ type: 'ask', trigger: 'manual' });
  });

  it('sends a screenshot note when there is one, and omits the field when not', () => {
    const { session, socket } = live();
    const shot = { mimeType: 'image/png', dataBase64: 'AAAA' } as const;

    session.askAboutScreen(shot);
    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({ type: 'screenshot', ...shot });

    session.askAboutScreen(shot, 'What is the complexity?');
    expect(JSON.parse(socket.sent.at(-1) as string).note).toBe('What is the complexity?');
  });

  it('drops an ask sent before the server is ready', () => {
    const session = build();
    session.connect('token-abc');
    FakeSocket.instances[0]!.open();

    session.ask('anything');

    expect(FakeSocket.instances[0]!.sent).toHaveLength(1); // hello only
  });

  it('reconnects after an unexpected close and authenticates again', () => {
    const session = build();
    session.connect('token-abc');
    FakeSocket.instances[0]!.open();
    ready(FakeSocket.instances[0]!);

    FakeSocket.instances[0]!.drop();
    vi.advanceTimersByTime(500);

    expect(FakeSocket.instances).toHaveLength(2);
    FakeSocket.instances[1]!.open();
    expect(JSON.parse(FakeSocket.instances[1]!.sent[0] as string).type).toBe('hello');
    expect(states.map((s) => s.state)).toContain('reconnecting');
  });

  it('keeps retrying well past the old five-attempt ceiling', () => {
    build().connect('token-abc');

    for (let i = 0; i < 20; i += 1) {
      FakeSocket.instances.at(-1)!.drop();
      vi.advanceTimersByTime(60_000);
    }

    expect(FakeSocket.instances).toHaveLength(21); // initial + 20 retries
    expect(states.at(-1)).toMatchObject({ state: 'reconnecting' });
  });

  it('stays on reconnecting rather than error while a retry is pending', () => {
    build().connect('token-abc');
    const socket = FakeSocket.instances[0]!;
    socket.open();

    // The failure path a dead server takes: onerror, then onclose.
    socket.onerror?.();
    socket.drop();

    expect(states.filter((s) => s.state === 'error')).toHaveLength(0);
    expect(states.at(-1)).toMatchObject({
      state: 'reconnecting',
      detail: 'reconnecting… (attempt 1)',
    });
  });

  it('backs off exponentially and stops growing at the 30s ceiling', () => {
    build().connect('token-abc');
    const openedAfter = (ms: number): number => {
      const before = FakeSocket.instances.length;
      vi.advanceTimersByTime(ms);
      return FakeSocket.instances.length - before;
    };

    FakeSocket.instances.at(-1)!.drop();
    expect(openedAfter(499)).toBe(0); // 500ms for the first retry
    expect(openedAfter(1)).toBe(1);

    FakeSocket.instances.at(-1)!.drop();
    expect(openedAfter(999)).toBe(0); // then 1000ms
    expect(openedAfter(1)).toBe(1);

    // Far past where doubling would have run away to hours.
    for (let i = 0; i < 12; i += 1) {
      FakeSocket.instances.at(-1)!.drop();
      vi.advanceTimersByTime(30_000);
    }
    FakeSocket.instances.at(-1)!.drop();
    expect(openedAfter(29_999)).toBe(0);
    expect(openedAfter(1)).toBe(1);
  });

  it('spreads the delay by ±20% at the extremes of the jitter', () => {
    build(() => 0).connect('token-abc');
    FakeSocket.instances[0]!.drop();
    vi.advanceTimersByTime(399); // 500 - 20%
    expect(FakeSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.instances).toHaveLength(2);

    FakeSocket.instances = [];
    build(() => 1).connect('token-def');
    FakeSocket.instances[0]!.drop();
    vi.advanceTimersByTime(599); // 500 + 20%
    expect(FakeSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it('reconnects immediately on retryNow instead of waiting out the backoff', () => {
    const session = build();
    session.connect('token-abc');
    FakeSocket.instances[0]!.drop();

    session.retryNow();

    expect(FakeSocket.instances).toHaveLength(2);
    // The cancelled timer must not fire a second socket later.
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it('ignores retryNow when no retry is pending', () => {
    const { session } = live();

    session.retryNow();

    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('counts the audio frames lost while the connection is down', () => {
    const { session, socket } = live();
    expect(session.droppedFrames).toBe(0);

    socket.drop();
    session.sendAudio(new Int16Array(3200));
    session.sendAudio(new Int16Array(3200));
    expect(session.droppedFrames).toBe(2);

    vi.advanceTimersByTime(60_000);
    ready(FakeSocket.instances[1]!);
    expect(session.droppedFrames).toBe(0);
  });

  it('does not reconnect after an intentional close', () => {
    const session = build();
    session.connect('token-abc');
    FakeSocket.instances[0]!.open();

    session.close();
    FakeSocket.instances[0]!.drop();
    vi.advanceTimersByTime(60_000);

    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('reports a message that is not valid JSON', () => {
    build().connect('token-abc');
    FakeSocket.instances[0]!.receiveRaw('{ not json');

    expect(states.at(-1)).toMatchObject({
      state: 'error',
      detail: 'unreadable message from server',
    });
    expect(messages).toHaveLength(0);
  });

  it('reports a message that does not match the protocol', () => {
    build().connect('token-abc');
    FakeSocket.instances[0]!.receive({ type: 'transcript', channel: 7, text: 'x', isFinal: true });

    expect(states.at(-1)).toMatchObject({ state: 'error', detail: 'unknown message from server' });
    expect(messages).toHaveLength(0);
  });

  it('ignores binary frames from the server', () => {
    build().connect('token-abc');
    FakeSocket.instances[0]!.receiveRaw(new ArrayBuffer(8));

    expect(messages).toHaveLength(0);
    expect(states.filter((s) => s.state === 'error')).toHaveLength(0);
  });

  const sentTypes = (socket: FakeSocket): unknown[] =>
    socket.sent
      .filter((frame): frame is string => typeof frame === 'string')
      .map((frame) => JSON.parse(frame).type);

  it('pings once the heartbeat interval has passed', () => {
    const { socket } = live();

    vi.advanceTimersByTime(15_000);

    expect(sentTypes(socket)).toEqual(['hello', 'ping']);
  });

  it('does not ping before the server is ready', () => {
    build().connect('token-abc');
    const socket = FakeSocket.instances[0]!;
    socket.open();

    vi.advanceTimersByTime(60_000);

    expect(sentTypes(socket)).toEqual(['hello']);
  });

  it('does not ping after an intentional close', () => {
    const { session, socket } = live();

    session.close();
    vi.advanceTimersByTime(60_000);

    expect(sentTypes(socket)).toEqual(['hello']);
  });

  it('closes a socket that stops answering the heartbeat', () => {
    const { socket } = live();

    vi.advanceTimersByTime(15_000); // ping goes out
    expect(socket.closed).toBe(false);
    vi.advanceTimersByTime(10_000); // no pong within the deadline

    expect(socket.closed).toBe(true);
    // The close then takes the ordinary reconnect path.
    socket.drop();
    vi.advanceTimersByTime(500);
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it('keeps the connection alive when the pong arrives, and hides it from the UI', () => {
    const { socket } = live();
    const before = messages.length;

    vi.advanceTimersByTime(15_000);
    socket.receive({ type: 'pong' });
    vi.advanceTimersByTime(15_000); // past the deadline the pong cleared

    expect(socket.closed).toBe(false);
    expect(messages).toHaveLength(before); // pong is plumbing, not a message
    expect(sentTypes(socket)).toEqual(['hello', 'ping', 'ping']);
  });
});
