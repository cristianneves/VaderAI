import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  clientMessageSchema,
  MAX_QUESTION_CHARS,
  MAX_SCREENSHOT_BASE64_CHARS,
  serverMessageSchema,
} from './index';

/**
 * The TypeScript half of the wire contract. The Java side asserts the same
 * fixtures in ContractFixturesTest — renaming a field on either side fails on
 * the other, which is the entire point of the fixtures.
 */
const load = (file: string): Record<string, unknown> =>
  JSON.parse(
    readFileSync(
      fileURLToPath(new URL(`../../../contracts/messages/${file}`, import.meta.url)),
      'utf8',
    ),
  ) as Record<string, unknown>;

const client = load('client.json');
const server = load('server.json');

describe('client fixtures', () => {
  it.each(['hello', 'ask', 'screenshot', 'ping'])('%s parses', (name) => {
    const parsed = clientMessageSchema.parse(client[name]);
    expect(parsed.type).toBe(name);
  });

  it('covers every client message type', () => {
    expect(Object.keys(client).sort()).toEqual(['ask', 'hello', 'ping', 'screenshot']);
  });

  it('rejects a hello carrying the wrong protocol version', () => {
    expect(() =>
      clientMessageSchema.parse({ ...(client['hello'] as object), protocolVersion: 2 }),
    ).toThrow();
  });

  it('rejects a hello with no token', () => {
    expect(() =>
      clientMessageSchema.parse({ ...(client['hello'] as object), accessToken: '' }),
    ).toThrow();
  });

  it('accepts an ask with no question — the auto-trigger sends none', () => {
    expect(clientMessageSchema.parse({ type: 'ask', trigger: 'auto' })).toEqual({
      type: 'ask',
      trigger: 'auto',
    });
  });

  it('rejects an empty question rather than asking the model about nothing', () => {
    expect(() =>
      clientMessageSchema.parse({ ...(client['ask'] as object), question: '' }),
    ).toThrow();
  });

  it('rejects a question over the ceiling', () => {
    expect(() =>
      clientMessageSchema.parse({
        ...(client['ask'] as object),
        question: 'x'.repeat(MAX_QUESTION_CHARS + 1),
      }),
    ).toThrow();
  });

  it('accepts an ask in coding mode', () => {
    expect(
      clientMessageSchema.parse({
        type: 'ask',
        trigger: 'manual',
        question: 'two sum',
        mode: 'coding',
      }),
    ).toMatchObject({ mode: 'coding' });
  });

  it('rejects a mode that is not one of the two prompts', () => {
    expect(() =>
      clientMessageSchema.parse({ ...(client['ask'] as object), mode: 'smart' }),
    ).toThrow();
  });

  it('accepts a JPEG screenshot at exactly the ceiling', () => {
    expect(
      clientMessageSchema.parse({
        type: 'screenshot',
        mimeType: 'image/jpeg',
        dataBase64: 'x'.repeat(MAX_SCREENSHOT_BASE64_CHARS),
      }),
    ).toMatchObject({ mimeType: 'image/jpeg' });
  });

  it('rejects a screenshot over the ceiling — one byte more closes the socket at 1009', () => {
    expect(() =>
      clientMessageSchema.parse({
        ...(client['screenshot'] as object),
        dataBase64: 'x'.repeat(MAX_SCREENSHOT_BASE64_CHARS + 1),
      }),
    ).toThrow();
  });

  it('rejects an image type the model does not take', () => {
    expect(() =>
      clientMessageSchema.parse({ ...(client['screenshot'] as object), mimeType: 'image/webp' }),
    ).toThrow();
  });
});

describe('server fixtures', () => {
  const names = [
    'ready',
    'transcript',
    'answer_start',
    'answer_delta',
    'answer_end',
    'error',
    'pong',
  ];

  it.each(names)('%s parses', (name) => {
    const parsed = serverMessageSchema.parse(server[name]);
    expect(parsed.type).toBe(name);
  });

  it('covers every server message type', () => {
    expect(Object.keys(server).sort()).toEqual([...names].sort());
  });

  it('rejects a transcript on a channel that does not exist', () => {
    expect(() =>
      serverMessageSchema.parse({ ...(server['transcript'] as object), channel: 2 }),
    ).toThrow();
  });

  it('rejects an unknown error code', () => {
    expect(() =>
      serverMessageSchema.parse({ ...(server['error'] as object), code: 'teapot' }),
    ).toThrow();
  });

  it('accepts the rate_limited code', () => {
    expect(
      serverMessageSchema.parse({ ...(server['error'] as object), code: 'rate_limited' }),
    ).toMatchObject({ code: 'rate_limited' });
  });

  it('rejects an unknown message type', () => {
    expect(() => serverMessageSchema.parse({ type: 'nope' })).toThrow();
  });
});
