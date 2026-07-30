import { describe, expect, it } from 'vitest';
import type { PracticeQuestion } from '../practice/practice';
import type { SessionDetail, SessionSummary } from './history';
import { toMarkdown } from './markdown';

const summary = (over: Partial<SessionSummary> = {}): SessionSummary => ({
  id: '6f1c9a2e-7b3d-4c8e-9f10-2a5b6c7d8e90',
  kind: 'live',
  startedAt: '2026-07-29T10:00:00Z',
  endedAt: '2026-07-29T10:12:30Z',
  turns: 2,
  answers: 1,
  practiceQuestions: 0,
  ...over,
});

const detail = (over: Partial<SessionDetail> = {}): SessionDetail => ({
  id: summary().id,
  kind: 'live',
  startedAt: '2026-07-29T10:00:00Z',
  endedAt: '2026-07-29T10:12:30Z',
  turns: [
    { channel: 0, content: 'Tell me about a hard bug.', createdAt: '2026-07-29T10:00:01Z' },
    { channel: 1, content: 'Sure, at Acme...', createdAt: '2026-07-29T10:00:09Z' },
  ],
  answers: [
    { content: 'At Acme I cut deploy time.', trigger: 'auto', createdAt: '2026-07-29T10:00:04Z' },
  ],
  ...over,
});

const question = (over: Partial<PracticeQuestion> = {}): PracticeQuestion => ({
  position: 0,
  question: 'Tell me about a hard bug.',
  answer: 'It was fine I guess.',
  structure: 2,
  specificity: 1,
  relevance: 4,
  feedback: 'You never named the project.',
  rewrite: 'At Acme I cut deploy time from 40 to 6 minutes.',
  ...over,
});

describe('toMarkdown for a live session', () => {
  it('writes a dated heading and the duration', () => {
    const out = toMarkdown(summary(), detail());

    expect(out).toContain('# Session — 2026-07-29');
    expect(out).toContain('Duration: 12m 30s');
  });

  it('labels the speakers and keeps the answer in place', () => {
    const out = toMarkdown(summary(), detail());
    const lines = out.split('\n').filter((line) => line !== '');

    expect(lines).toContain('**Interviewer:** Tell me about a hard bug.');
    expect(lines).toContain('**You:** Sure, at Acme...');
    // Interleaved by timestamp, so the answer lands between the two turns.
    expect(out.indexOf('cut deploy time')).toBeLessThan(out.indexOf('Sure, at Acme'));
  });

  it('quotes answers so they read apart from the transcript', () => {
    expect(toMarkdown(summary(), detail())).toContain('> At Acme I cut deploy time.');
  });

  it('notes when an answer was about the screen', () => {
    const out = toMarkdown(
      summary(),
      detail({
        turns: [],
        answers: [
          { content: 'O(n log n).', trigger: 'screenshot', createdAt: '2026-07-29T10:00:00Z' },
        ],
      }),
    );

    // There is no spoken question above it, so the document has to say why.
    expect(out).toContain('_(about the screen)_');
  });

  it('still produces a valid document for an empty session', () => {
    const out = toMarkdown(summary({ turns: 0, answers: 0 }), detail({ turns: [], answers: [] }));

    expect(out).toContain('# Session');
    expect(out).toContain('_Nothing was captured in this session._');
    expect(out.endsWith('\n')).toBe(true);
  });

  it('renders an unfinished session without inventing a duration', () => {
    expect(toMarkdown(summary({ endedAt: null }), detail({ endedAt: null }))).toContain(
      'Duration: unfinished',
    );
  });

  it('indents a multi-line answer as one quote block', () => {
    const out = toMarkdown(
      summary(),
      detail({
        turns: [],
        answers: [
          { content: 'First line.\nSecond line.', trigger: 'auto', createdAt: '2026-07-29T10:00:00Z' },
        ],
      }),
    );

    expect(out).toContain('> First line.\n> Second line.');
  });
});

describe('toMarkdown for a practice session', () => {
  const practice = summary({ kind: 'practice', answers: 0, practiceQuestions: 1 });

  it('writes the question, the scores, the feedback, and the rewrite', () => {
    const out = toMarkdown(practice, detail({ kind: 'practice' }), [question()]);

    expect(out).toContain('# Practice session — 2026-07-29');
    expect(out).toContain('## 1. Tell me about a hard bug.');
    expect(out).toContain('**Your answer:** It was fine I guess.');
    expect(out).toContain('Structure 2/5 · Specificity 1/5 · Relevance 4/5');
    expect(out).toContain('**Feedback:** You never named the project.');
    expect(out).toContain('> At Acme I cut deploy time from 40 to 6 minutes.');
  });

  it('marks a question that was never answered', () => {
    const out = toMarkdown(practice, detail({ kind: 'practice' }), [
      question({ answer: null, structure: null, feedback: null, rewrite: null }),
    ]);

    expect(out).toContain('_Not answered._');
    expect(out).not.toContain('Structure');
  });

  it('does not fall back to the transcript', () => {
    // A practice run's spoken answers live on the question rows; rendering the
    // raw transcript as well would print everything twice.
    const out = toMarkdown(practice, detail({ kind: 'practice' }), [question()]);

    expect(out).not.toContain('## Transcript');
  });

  it('says so when no questions were generated', () => {
    const out = toMarkdown(practice, detail({ kind: 'practice' }), []);

    expect(out).toContain('_No questions were generated._');
  });
});
