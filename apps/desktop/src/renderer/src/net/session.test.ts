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
  /** What the token provider hands back next. Reassign to simulate a refresh. */
  let token: string | null;

  /** 0.5 lands the jitter dead centre, so backoff delays stay exact in tests. */
  const build = (random = (): number => 0.5): SessionSocket =>
    new SessionSocket(
      'ws://localhost:8787/v1/session',
      {
        onMessage: (message) => messages.push(message),
        onState: (state, detail) => states.push({ state, detail }),
        token: () => Promise.resolve(token),
      },
      () => new FakeSocket() as unknown as WebSocket,
      random,
    );

  const ready = (socket: FakeSocket): void =>
    socket.receive({ type: 'ready', sessionId: SESSION_ID });

  /** hello waits on the token provider, so opening settles a microtask later. */
  const opened = async (socket: FakeSocket): Promise<FakeSocket> => {
    socket.open();
    await vi.advanceTimersByTimeAsync(0);
    return socket;
  };

  /** Connects, opens and readies a session, returning both halves. */
  const live = async (): Promise<{ session: SessionSocket; socket: FakeSocket }> => {
    const session = build();
    session.connect();
    const socket = await opened(FakeSocket.instances[0]!);
    ready(socket);
    return { session, socket };
  };

  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.instances = [];
    messages = [];
    states = [];
    token = 'token-abc';
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('sends hello as its very first frame', async () => {
    build().connect();
    const socket = await opened(FakeSocket.instances[0]!);

    expect(socket.sent).toHaveLength(1);
    expect(JSON.parse(socket.sent[0] as string)).toEqual({
      type: 'hello',
      protocolVersion: PROTOCOL_VERSION,
      accessToken: 'token-abc',
    });
  });

  it('reports ready and forwards server messages', async () => {
    const { socket } = await live();

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
    build().connect();

    expect(FakeSocket.instances[0]!.binaryType).toBe('arraybuffer');
  });

  it('drops audio until the server is ready', async () => {
    const session = build();
    session.connect();
    const socket = await opened(FakeSocket.instances[0]!);

    session.sendAudio(new Int16Array(3200));

    expect(socket.sent).toHaveLength(1); // hello only
  });

  it('sends audio once ready', async () => {
    const { session, socket } = await live();

    session.sendAudio(new Int16Array(3200));

    expect(socket.sent).toHaveLength(2);
    expect(socket.sent[1]).toBeInstanceOf(ArrayBuffer);
    expect((socket.sent[1] as ArrayBuffer).byteLength).toBe(6400);
  });

  it('asks with no question, which answers the interviewer instead', async () => {
    const { session, socket } = await live();

    session.ask();

    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({ type: 'ask', trigger: 'manual' });
  });

  it('carries a typed question', async () => {
    const { session, socket } = await live();

    session.ask('Explain that more simply.');

    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({
      type: 'ask',
      trigger: 'manual',
      question: 'Explain that more simply.',
    });
  });

  it('trims a question and omits one that was only whitespace', async () => {
    const { session, socket } = await live();

    session.ask('  padded  ');
    expect(JSON.parse(socket.sent.at(-1) as string).question).toBe('padded');

    session.ask('   ');
    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({ type: 'ask', trigger: 'manual' });
  });

  it('sends a screenshot note when there is one, and omits the field when not', async () => {
    const { session, socket } = await live();
    const shot = { mimeType: 'image/jpeg', dataBase64: 'AAAA' } as const;

    session.askAboutScreen(shot);
    expect(JSON.parse(socket.sent.at(-1) as string)).toEqual({ type: 'screenshot', ...shot });

    session.askAboutScreen(shot, 'What is the complexity?');
    expect(JSON.parse(socket.sent.at(-1) as string).note).toBe('What is the complexity?');
  });

  it('drops an ask sent before the server is ready', async () => {
    const session = build();
    session.connect();
    const socket = await opened(FakeSocket.instances[0]!);

    session.ask('anything');

    expect(socket.sent).toHaveLength(1); // hello only
  });

  it('reconnects after an unexpected close and authenticates again', async () => {
    const { socket } = await live();

    socket.drop();
    await vi.advanceTimersByTimeAsync(500);

    expect(FakeSocket.instances).toHaveLength(2);
    const second = await opened(FakeSocket.instances[1]!);
    expect(JSON.parse(second.sent[0] as string).type).toBe('hello');
    expect(states.map((s) => s.state)).toContain('reconnecting');
  });

  it('keeps retrying well past the old five-attempt ceiling', () => {
    build().connect();

    for (let i = 0; i < 20; i += 1) {
      FakeSocket.instances.at(-1)!.drop();
      vi.advanceTimersByTime(60_000);
    }

    expect(FakeSocket.instances).toHaveLength(21); // initial + 20 retries
    expect(states.at(-1)).toMatchObject({ state: 'reconnecting' });
  });

  it('stays on reconnecting rather than error while a retry is pending', () => {
    build().connect();
    const socket = FakeSocket.instances[0]!;

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
    build().connect();
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
    build(() => 0).connect();
    FakeSocket.instances[0]!.drop();
    vi.advanceTimersByTime(399); // 500 - 20%
    expect(FakeSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.instances).toHaveLength(2);

    FakeSocket.instances = [];
    build(() => 1).connect();
    FakeSocket.instances[0]!.drop();
    vi.advanceTimersByTime(599); // 500 + 20%
    expect(FakeSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it('reconnects immediately on retryNow instead of waiting out the backoff', () => {
    const session = build();
    session.connect();
    FakeSocket.instances[0]!.drop();

    session.retryNow();

    expect(FakeSocket.instances).toHaveLength(2);
    // The cancelled timer must not fire a second socket later.
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it('ignores retryNow when no retry is pending', async () => {
    const { session } = await live();

    session.retryNow();

    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('counts the audio frames lost while the connection is down', async () => {
    const { session, socket } = await live();
    expect(session.droppedFrames).toBe(0);

    socket.drop();
    session.sendAudio(new Int16Array(3200));
    session.sendAudio(new Int16Array(3200));
    expect(session.droppedFrames).toBe(2);

    await vi.advanceTimersByTimeAsync(60_000);
    ready(FakeSocket.instances[1]!);
    expect(session.droppedFrames).toBe(0);
  });

  it('does not reconnect after an intentional close', async () => {
    const { session, socket } = await live();

    session.close();
    socket.drop();
    await vi.advanceTimersByTimeAsync(60_000);

    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('reports a message that is not valid JSON', () => {
    build().connect();
    FakeSocket.instances[0]!.receiveRaw('{ not json');

    expect(states.at(-1)).toMatchObject({
      state: 'error',
      detail: 'unreadable message from server',
    });
    expect(messages).toHaveLength(0);
  });

  it('reports a message that does not match the protocol', () => {
    build().connect();
    FakeSocket.instances[0]!.receive({ type: 'transcript', channel: 7, text: 'x', isFinal: true });

    expect(states.at(-1)).toMatchObject({ state: 'error', detail: 'unknown message from server' });
    expect(messages).toHaveLength(0);
  });

  it('ignores binary frames from the server', () => {
    build().connect();
    FakeSocket.instances[0]!.receiveRaw(new ArrayBuffer(8));

    expect(messages).toHaveLength(0);
    expect(states.filter((s) => s.state === 'error')).toHaveLength(0);
  });

  // --- Heartbeat -----------------------------------------------------------

  const sentTypes = (socket: FakeSocket): unknown[] =>
    socket.sent
      .filter((frame): frame is string => typeof frame === 'string')
      .map((frame) => JSON.parse(frame).type);

  it('pings once the heartbeat interval has passed', async () => {
    const { socket } = await live();

    vi.advanceTimersByTime(15_000);

    expect(sentTypes(socket)).toEqual(['hello', 'ping']);
  });

  it('does not ping before the server is ready', async () => {
    build().connect();
    const socket = await opened(FakeSocket.instances[0]!);

    vi.advanceTimersByTime(60_000);

    expect(sentTypes(socket)).toEqual(['hello']);
  });

  it('does not ping after an intentional close', async () => {
    const { session, socket } = await live();

    session.close();
    vi.advanceTimersByTime(60_000);

    expect(sentTypes(socket)).toEqual(['hello']);
  });

  it('closes a socket that stops answering the heartbeat', async () => {
    const { socket } = await live();

    vi.advanceTimersByTime(15_000); // ping goes out
    expect(socket.closed).toBe(false);
    vi.advanceTimersByTime(10_000); // no pong within the deadline

    expect(socket.closed).toBe(true);
    // The close then takes the ordinary reconnect path.
    socket.drop();
    vi.advanceTimersByTime(500);
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it('keeps the connection alive when the pong arrives, and hides it from the UI', async () => {
    const { socket } = await live();
    const before = messages.length;

    vi.advanceTimersByTime(15_000);
    socket.receive({ type: 'pong' });
    vi.advanceTimersByTime(15_000); // past the deadline the pong cleared

    expect(socket.closed).toBe(false);
    expect(messages).toHaveLength(before); // pong is plumbing, not a message
    expect(sentTypes(socket)).toEqual(['hello', 'ping', 'ping']);
  });

  // --- Credentials ---------------------------------------------------------

  it('sends the token the provider has now, not the one captured at connect', async () => {
    const session = build();
    session.connect();
    const first = await opened(FakeSocket.instances[0]!);
    ready(first);
    expect(JSON.parse(first.sent[0] as string).accessToken).toBe('token-abc');

    // What Supabase's auto-refresh does while the session is up.
    token = 'token-refreshed';
    first.drop();
    await vi.advanceTimersByTimeAsync(500);
    const second = await opened(FakeSocket.instances[1]!);

    expect(JSON.parse(second.sent[0] as string).accessToken).toBe('token-refreshed');
  });

  it('stops retrying when the server rejects the current credential', async () => {
    const { socket } = await live();

    socket.receive({ type: 'error', code: 'unauthorized', message: 'invalid access token' });
    socket.drop();
    await vi.advanceTimersByTimeAsync(60_000);

    // The regression this guards: retrying re-sent the same rejected token
    // every 30s forever once Phase 10 made reconnection unbounded.
    expect(FakeSocket.instances).toHaveLength(1);
    expect(states.at(-1)).toMatchObject({ state: 'error', detail: 'invalid access token' });
    expect(messages.at(-1)).toMatchObject({ code: 'unauthorized' });
  });

  it('stays up when the failure is not about credentials', async () => {
    const { socket } = await live();

    socket.receive({ type: 'error', code: 'stt_failed', message: 'transcription unavailable' });

    expect(socket.closed).toBe(false);
    expect(states.at(-1)).toMatchObject({ state: 'ready' });
    expect(messages.at(-1)).toMatchObject({ code: 'stt_failed' });
  });

  it('stops without handshaking when there is no session left to resume', async () => {
    token = null;
    build().connect();
    const socket = await opened(FakeSocket.instances[0]!);

    expect(socket.sent).toHaveLength(0);
    expect(socket.closed).toBe(true);
    expect(states.at(-1)).toMatchObject({ state: 'error' });
    // Reported as the frame the server would have sent, so the UI needs no
    // special case for a session that ran out locally.
    expect(messages.at(-1)).toMatchObject({ type: 'error', code: 'unauthorized' });

    await vi.advanceTimersByTimeAsync(60_000);
    expect(FakeSocket.instances).toHaveLength(1);
  });

  it('does not send hello from a socket a reconnect already replaced', async () => {
    const waiting: ((value: string | null) => void)[] = [];
    const session = new SessionSocket(
      'ws://localhost:8787/v1/session',
      {
        onMessage: (message) => messages.push(message),
        onState: (state, detail) => states.push({ state, detail }),
        token: () => new Promise((resolve) => waiting.push(resolve)),
      },
      () => new FakeSocket() as unknown as WebSocket,
      () => 0.5,
    );

    session.connect();
    const first = FakeSocket.instances[0]!;
    first.open(); // its token request is still in flight
    first.drop();
    await vi.advanceTimersByTimeAsync(500);
    expect(FakeSocket.instances).toHaveLength(2);

    waiting[0]!('token-late'); // the superseded socket's token finally arrives
    await vi.advanceTimersByTimeAsync(0);

    expect(first.sent).toHaveLength(0);
  });
});
