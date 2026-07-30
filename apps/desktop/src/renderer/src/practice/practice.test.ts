import { describe, expect, it } from 'vitest';
import {
  applyPractice,
  currentQuestion,
  isLastQuestion,
  NO_PRACTICE,
  type PracticeEvent,
  type PracticeQuestion,
  type PracticeState,
} from './practice';

const asked = (position: number): PracticeQuestion => ({
  position,
  question: `Question ${position}?`,
  answer: null,
  structure: null,
  specificity: null,
  relevance: null,
  feedback: null,
  rewrite: null,
});

const graded = (position: number, structure = 2): PracticeQuestion => ({
  ...asked(position),
  answer: 'It was fine I guess.',
  structure,
  specificity: 1,
  relevance: 4,
  feedback: 'You never named the project.',
  rewrite: 'At Acme I cut deploy time from 40 to 6 minutes.',
});

const THREE = [asked(0), asked(1), asked(2)];

const said = (text: string, isFinal = true, channel = 1): PracticeEvent => ({
  type: 'transcript',
  channel,
  text,
  isFinal,
});

const run = (events: PracticeEvent[], from: PracticeState = NO_PRACTICE): PracticeState =>
  events.reduce(applyPractice, from);

/** Loaded and sitting on question 0 with the mic open. */
const answering = (): PracticeState =>
  run([{ type: 'start' }, { type: 'questions', questions: THREE }]);

describe('applyPractice', () => {
  it('moves to the first question once the set arrives', () => {
    const state = answering();

    expect(state.step).toBe('answering');
    expect(state.current).toBe(0);
    expect(currentQuestion(state)?.question).toBe('Question 0?');
  });

  it('resumes at the first ungraded question', () => {
    // A reopened panel should not make the user answer everything again.
    const state = run([{ type: 'questions', questions: [graded(0), graded(1), asked(2)] }]);

    expect(state.current).toBe(2);
    expect(state.step).toBe('answering');
  });

  it('accumulates channel-1 finals into the spoken answer', () => {
    const state = run([said('At Acme'), said('I cut deploy time.')], answering());

    expect(state.spoken).toBe('At Acme I cut deploy time.');
  });

  it('ignores the interviewer channel', () => {
    // Channel 0 is silent in practice mode, but a stray final must never be
    // credited to the candidate.
    const state = run([said('not the candidate', true, 0)], answering());

    expect(state.spoken).toBe('');
  });

  it('ignores interim results', () => {
    // Interims are rewritten several times a second; appending them would
    // duplicate every phrase.
    const state = run(
      [said('At Ac', false), said('At Acme', false), said('At Acme.')],
      answering(),
    );

    expect(state.spoken).toBe('At Acme.');
  });

  it('ignores transcript before the interview starts', () => {
    expect(run([said('talking to myself')]).spoken).toBe('');
  });

  it('ignores transcript once the answer is being graded', () => {
    // Whatever they say while waiting belongs to no question.
    const state = run(
      [said('my answer'), { type: 'submit' }, said('and another thing')],
      answering(),
    );

    expect(state.step).toBe('grading');
    expect(state.spoken).toBe('my answer');
  });

  it('ignores blank finals', () => {
    const state = run([said('   ')], answering());

    expect(state.spoken).toBe('');
  });

  it('refuses to submit before anything is said', () => {
    const state = run([{ type: 'submit' }], answering());

    expect(state.step).toBe('answering');
  });

  it('submits once something has been said', () => {
    const state = run([said('an answer'), { type: 'submit' }], answering());

    expect(state.step).toBe('grading');
  });

  it('stores the grade against the right question', () => {
    const state = run(
      [said('an answer'), { type: 'submit' }, { type: 'graded', question: graded(0, 3) }],
      answering(),
    );

    expect(state.step).toBe('graded');
    expect(state.questions[0]?.structure).toBe(3);
    expect(state.questions[1]?.structure).toBeNull();
  });

  it('advances to the next question and clears the spoken answer', () => {
    const state = run(
      [
        said('first answer'),
        { type: 'submit' },
        { type: 'graded', question: graded(0) },
        { type: 'next' },
      ],
      answering(),
    );

    expect(state.step).toBe('answering');
    expect(state.current).toBe(1);
    expect(state.spoken).toBe('');
  });

  it('waits on the report after the last question', () => {
    let state = answering();
    for (let position = 0; position < THREE.length; position++) {
      state = run(
        [
          said('an answer'),
          { type: 'submit' },
          { type: 'graded', question: graded(position) },
          { type: 'next' },
        ],
        state,
      );
    }

    // The caller fetches the report; the reducer only knows it is pending.
    expect(state.step).toBe('loading');
  });

  it('ignores next before a grade comes back', () => {
    const state = run([said('an answer'), { type: 'submit' }, { type: 'next' }], answering());

    expect(state.step).toBe('grading');
  });

  it('shows the report', () => {
    const report = { questions: [graded(0)], themes: ['no numbers'], summary: 'Name one metric.' };
    const state = run([{ type: 'report', report }], answering());

    expect(state.step).toBe('report');
    expect(state.report?.themes).toEqual(['no numbers']);
  });

  it('leaves the user on the question after a failure so they can retry', () => {
    const state = run(
      [said('an answer'), { type: 'submit' }, { type: 'error', message: '500 boom' }],
      answering(),
    );

    expect(state.step).toBe('answering');
    expect(state.error).toBe('500 boom');
    expect(state.spoken).toBe('an answer');
  });

  it('falls back to idle when the question set itself failed', () => {
    const state = run([{ type: 'start' }, { type: 'error', message: '409 add a job description' }]);

    expect(state.step).toBe('idle');
    expect(state.error).toContain('job description');
  });

  it('resets to nothing', () => {
    expect(run([{ type: 'reset' }], answering())).toEqual(NO_PRACTICE);
  });

  it('does not mutate the state it was given', () => {
    const before = run([said('one')], answering());
    applyPractice(before, said('two'));

    expect(before.spoken).toBe('one');
  });
});

describe('isLastQuestion', () => {
  it('is false on an empty set', () => {
    expect(isLastQuestion(NO_PRACTICE)).toBe(false);
  });

  it('is true only on the final question', () => {
    const state = answering();

    expect(isLastQuestion(state)).toBe(false);
    expect(isLastQuestion({ ...state, current: 2 })).toBe(true);
  });
});
