import { errorSchema } from '@vaderai/protocol';
import { describe, expect, it } from 'vitest';
import { severityOf } from './problem';

describe('severityOf', () => {
  it('treats a rejected credential as fatal — nothing recovers without signing in', () => {
    expect(severityOf('unauthorized')).toBe('fatal');
  });

  it('treats provider failures as transient, because the next question may work', () => {
    expect(severityOf('stt_failed')).toBe('transient');
    expect(severityOf('llm_failed')).toBe('transient');
  });

  it('treats a rejected request or a server fault as a bug', () => {
    expect(severityOf('bad_request')).toBe('bug');
    expect(severityOf('internal')).toBe('bug');
  });

  it('never reports a provider failure as fatal — that would strand a live session', () => {
    expect(severityOf('stt_failed')).not.toBe('fatal');
    expect(severityOf('llm_failed')).not.toBe('fatal');
  });

  /** Guards the record against the protocol growing a code nobody mapped. */
  it('maps every code the protocol defines', () => {
    for (const code of errorSchema.shape.code.options) {
      expect(['fatal', 'transient', 'bug']).toContain(severityOf(code));
    }
  });
});
