import type { ServerMessage } from '@vaderai/protocol';
import { describe, expect, it } from 'vitest';
import { applyAnswer, NO_ANSWER, type AnswerState } from './answer';

const A = '6f1c9a2e-7b3d-4c8e-9f10-2a5b6c7d8e90';
const B = 'b2d4f6a8-1c3e-5079-8b1d-3f5a7c9e0b2d';

const start = (id: string): ServerMessage => ({ type: 'answer_start', answerId: id });
const delta = (id: string, text: string): ServerMessage => ({
  type: 'answer_delta',
  answerId: id,
  text,
});
const end = (id: string): ServerMessage => ({ type: 'answer_end', answerId: id });

const run = (messages: ServerMessage[], from: AnswerState = NO_ANSWER): AnswerState =>
  messages.reduce(applyAnswer, from);

describe('applyAnswer', () => {
  it('accumulates deltas in order', () => {
    const state = run([start(A), delta(A, 'At Acme '), delta(A, 'I cut deploy time.')]);

    expect(state.text).toBe('At Acme I cut deploy time.');
    expect(state.streaming).toBe(true);
  });

  it('stops streaming when the answer ends', () => {
    const state = run([start(A), delta(A, 'done'), end(A)]);

    expect(state).toEqual({ id: A, text: 'done', streaming: false });
  });

  it('clears the previous answer when a new one starts', () => {
    const state = run([start(A), delta(A, 'stale'), start(B)]);

    expect(state).toEqual({ id: B, text: '', streaming: true });
  });

  it('drops a straggling delta from a cancelled answer', () => {
    // Cancel-in-flight: the old stream can emit one more delta after the new
    // question has already started.
    const state = run([start(A), start(B), delta(A, 'from the cancelled answer')]);

    expect(state.text).toBe('');
    expect(state.id).toBe(B);
  });

  it('ignores a delta that arrives before any answer started', () => {
    expect(run([delta(A, 'orphan')])).toEqual(NO_ANSWER);
  });

  it('ignores an end for a different answer', () => {
    const state = run([start(B), delta(B, 'still going'), end(A)]);

    expect(state.streaming).toBe(true);
  });

  it('leaves unrelated messages alone', () => {
    const state = run([
      start(A),
      { type: 'transcript', channel: 0, text: 'a question', isFinal: true },
    ]);

    expect(state).toEqual({ id: A, text: '', streaming: true });
  });

  it('does not mutate the state it was given', () => {
    const before = run([start(A), delta(A, 'one')]);
    applyAnswer(before, delta(A, 'two'));

    expect(before.text).toBe('one');
  });
});
