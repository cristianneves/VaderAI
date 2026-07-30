import { describe, expect, it } from 'vitest';
import {
  describeSession,
  exportFileName,
  formatDuration,
  timelineOf,
  type SessionDetail,
  type SessionSummary,
} from './history';

const summary = (over: Partial<SessionSummary> = {}): SessionSummary => ({
  id: '6f1c9a2e-7b3d-4c8e-9f10-2a5b6c7d8e90',
  kind: 'live',
  startedAt: '2026-07-29T10:00:00Z',
  endedAt: '2026-07-29T10:12:30Z',
  turns: 4,
  answers: 2,
  practiceQuestions: 0,
  ...over,
});

const detail = (over: Partial<SessionDetail> = {}): SessionDetail => ({
  id: summary().id,
  kind: 'live',
  startedAt: '2026-07-29T10:00:00Z',
  endedAt: '2026-07-29T10:12:30Z',
  turns: [],
  answers: [],
  ...over,
});

describe('timelineOf', () => {
  it('interleaves turns and answers by timestamp', () => {
    const state = timelineOf(
      detail({
        turns: [
          { channel: 0, content: 'Tell me about a hard bug.', createdAt: '2026-07-29T10:00:01Z' },
          { channel: 1, content: 'Sure, at Acme...', createdAt: '2026-07-29T10:00:09Z' },
        ],
        answers: [
          { content: 'At Acme I cut deploy time.', trigger: 'auto', createdAt: '2026-07-29T10:00:04Z' },
        ],
      }),
    );

    expect(state.map((entry) => entry.kind)).toEqual(['interviewer', 'answer', 'you']);
    expect(state[1]?.text).toBe('At Acme I cut deploy time.');
  });

  it('puts an answer after a turn recorded in the same instant', () => {
    // An answer cannot precede the words that prompted it, so a tie has to break
    // towards the transcript.
    const at = '2026-07-29T10:00:00Z';
    const state = timelineOf(
      detail({
        turns: [{ channel: 0, content: 'question', createdAt: at }],
        answers: [{ content: 'answer', trigger: 'auto', createdAt: at }],
      }),
    );

    expect(state.map((entry) => entry.kind)).toEqual(['interviewer', 'answer']);
  });

  it('keeps the trigger so a screenshot answer can be labelled', () => {
    const state = timelineOf(
      detail({
        answers: [{ content: 'O(n log n)', trigger: 'screenshot', createdAt: '2026-07-29T10:00:00Z' }],
      }),
    );

    expect(state[0]?.trigger).toBe('screenshot');
  });

  it('is empty for a session that captured nothing', () => {
    expect(timelineOf(detail())).toEqual([]);
  });
});

describe('formatDuration', () => {
  it('formats minutes and seconds', () => {
    expect(formatDuration('2026-07-29T10:00:00Z', '2026-07-29T10:12:30Z')).toBe('12m 30s');
  });

  it('drops the seconds when they are zero', () => {
    expect(formatDuration('2026-07-29T10:00:00Z', '2026-07-29T10:12:00Z')).toBe('12m');
  });

  it('formats a short session in seconds', () => {
    expect(formatDuration('2026-07-29T10:00:00Z', '2026-07-29T10:00:42Z')).toBe('42s');
  });

  it('says unfinished when the session never closed', () => {
    // closeSession only runs on a clean socket close, so a crash leaves this null.
    expect(formatDuration('2026-07-29T10:00:00Z', null)).toBe('unfinished');
  });

  it('does not render a negative duration', () => {
    expect(formatDuration('2026-07-29T10:12:00Z', '2026-07-29T10:00:00Z')).toBe('unknown');
  });
});

describe('describeSession', () => {
  it('counts answers for a live session', () => {
    expect(describeSession(summary())).toBe('2 answers · 12m 30s');
  });

  it('counts questions for a practice session', () => {
    // A practice run keeps its answers on its question rows, so the answer count
    // is always zero and would read as an empty session.
    expect(describeSession(summary({ kind: 'practice', answers: 0, practiceQuestions: 5 }))).toBe(
      '5 questions · 12m 30s',
    );
  });

  it('does not pluralise a single item', () => {
    expect(describeSession(summary({ answers: 1 }))).toContain('1 answer ·');
  });
});

describe('exportFileName', () => {
  it('is dated, kinded, and unique per session', () => {
    expect(exportFileName(summary())).toBe('vaderai-live-2026-07-29-6f1c9a2e.md');
  });

  it('marks a practice session', () => {
    expect(exportFileName(summary({ kind: 'practice' }))).toContain('practice');
  });
});
