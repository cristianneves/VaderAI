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

  const build = (): SessionSocket =>
    new SessionSocket(
      'ws://localhost:8787/v1/session',
      {
        onMessage: (message) => messages.push(message),
        onState: (state, detail) => states.push({ state, detail }),
      },
      () => new FakeSocket() as unknown as WebSocket,
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

  it('gives up after repeated failures instead of reconnecting forever', () => {
    build().connect('token-abc');

    for (let i = 0; i < 8; i += 1) {
      FakeSocket.instances.at(-1)!.drop();
      vi.advanceTimersByTime(60_000);
    }

    expect(FakeSocket.instances.length).toBeLessThanOrEqual(6); // initial + 5 retries
    expect(states.at(-1)).toMatchObject({ state: 'error' });
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
});
