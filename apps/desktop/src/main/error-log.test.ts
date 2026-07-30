import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ErrorLog, formatEntry, MAX_LOG_BYTES } from './error-log';

const WHEN = new Date('2026-07-30T12:00:00.000Z');

describe('formatEntry', () => {
  it('records an Error with its stack', () => {
    const error = new Error('deepgram socket closed');
    const entry = formatEntry(WHEN, 'main', error);
    expect(entry).toContain('[2026-07-30T12:00:00.000Z] main:');
    expect(entry).toContain('deepgram socket closed');
    expect(entry.endsWith('\n')).toBe(true);
  });

  it('indents continuation lines so a stack stays one entry', () => {
    const error = new Error('boom');
    error.stack = 'Error: boom\n    at one\n    at two';
    const lines = formatEntry(WHEN, 'renderer', error).trimEnd().split('\n');
    expect(lines[0]).toContain('Error: boom');
    expect(lines.slice(1).every((line) => line.startsWith('    '))).toBe(true);
  });

  it('accepts a plain string', () => {
    expect(formatEntry(WHEN, 'renderer', 'plain failure')).toContain('renderer: plain failure');
  });

  it('falls back to String() for a value JSON cannot take', () => {
    const circular: Record<string, unknown> = {};
    circular['self'] = circular;
    expect(() => formatEntry(WHEN, 'main', circular)).not.toThrow();
    expect(formatEntry(WHEN, 'main', circular)).toContain('main: [object Object]');
  });

  it('handles a thrown Error with no stack at all', () => {
    const error = new Error('no stack');
    delete error.stack;
    expect(formatEntry(WHEN, 'main', error)).toContain('Error: no stack');
  });
});

describe('ErrorLog', () => {
  let dir: string;
  let log: ErrorLog;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'vaderai-log-'));
    log = new ErrorLog(join(dir, 'logs', 'vaderai.log'));
  });

  afterEach(() => rmSync(dir, { recursive: true, force: true }));

  it('creates the directory and appends entries in order', () => {
    log.append('main', new Error('first'), WHEN);
    log.append('renderer', new Error('second'), WHEN);

    const contents = readFileSync(log.file, 'utf8');
    expect(contents.indexOf('first')).toBeLessThan(contents.indexOf('second'));
  });

  it('rotates to a .1 sibling once past the ceiling, keeping the old entries', () => {
    log.append('main', 'seed', WHEN);
    writeFileSync(log.file, 'x'.repeat(MAX_LOG_BYTES));

    log.append('main', new Error('after rotate'), WHEN);

    expect(readFileSync(`${log.file}.1`, 'utf8').length).toBe(MAX_LOG_BYTES);
    const current = readFileSync(log.file, 'utf8');
    expect(current).toContain('after rotate');
    expect(current.length).toBeLessThan(MAX_LOG_BYTES);
  });

  it('does not rotate while under the ceiling', () => {
    log.append('main', 'one', WHEN);
    log.append('main', 'two', WHEN);
    expect(() => readFileSync(`${log.file}.1`, 'utf8')).toThrow();
  });

  it('swallows an unwritable path rather than taking the app down with it', () => {
    // The log file's own parent is a file, so mkdir and append both fail.
    const blocked = join(dir, 'blocker');
    writeFileSync(blocked, 'not a directory');
    const broken = new ErrorLog(join(blocked, 'nested', 'vaderai.log'));

    expect(() => broken.append('main', new Error('while already failing'))).not.toThrow();
  });
});
