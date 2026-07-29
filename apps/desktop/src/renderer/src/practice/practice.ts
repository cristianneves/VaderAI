import { Channel } from '@vaderai/protocol';

/** One question and, once graded, the answer that was given to it. */
export interface PracticeQuestion {
  position: number;
  question: string;
  answer: string | null;
  structure: number | null;
  specificity: number | null;
  relevance: number | null;
  feedback: string | null;
  rewrite: string | null;
}

export interface PracticeReport {
  questions: PracticeQuestion[];
  themes: string[];
  summary: string;
}

export type PracticeStep =
  /** Nothing started. */
  | 'idle'
  /** Waiting on the server — the question set, or the closing report. */
  | 'loading'
  /** A question is on screen and the mic is open. */
  | 'answering'
  /** The answer has been sent for grading. */
  | 'grading'
  /** The grade is back and being read. */
  | 'graded'
  /** The session is over and the report is on screen. */
  | 'report';

export interface PracticeState {
  step: PracticeStep;
  questions: PracticeQuestion[];
  /** Index into {@link questions}, not a position — they happen to match today. */
  current: number;
  /** Channel-1 finals for the current question, joined as they arrive. */
  spoken: string;
  report: PracticeReport | null;
  error: string | null;
}

export type PracticeEvent =
  | { type: 'start' }
  | { type: 'questions'; questions: PracticeQuestion[] }
  | { type: 'transcript'; channel: number; text: string; isFinal: boolean }
  | { type: 'submit' }
  | { type: 'graded'; question: PracticeQuestion }
  | { type: 'next' }
  | { type: 'report'; report: PracticeReport }
  | { type: 'error'; message: string }
  | { type: 'reset' };

export const NO_PRACTICE: PracticeState = {
  step: 'idle',
  questions: [],
  current: 0,
  spoken: '',
  report: null,
  error: null,
};

export const isGraded = (question: PracticeQuestion): boolean => question.structure !== null;

export const currentQuestion = (state: PracticeState): PracticeQuestion | null =>
  state.questions[state.current] ?? null;

export const isLastQuestion = (state: PracticeState): boolean =>
  state.questions.length > 0 && state.current === state.questions.length - 1;

/**
 * The mock-interview state machine.
 *
 * <p>Answers are spoken, so the text comes from the transcript stream rather
 * than a form: channel-1 finals accumulate while a question is on screen, and
 * everything else on the wire is ignored. Interims are skipped because they are
 * rewritten several times a second — appending them would duplicate every
 * phrase.
 */
export function applyPractice(state: PracticeState, event: PracticeEvent): PracticeState {
  switch (event.type) {
    case 'start':
      return { ...NO_PRACTICE, step: 'loading' };

    case 'questions': {
      // Resume at the first ungraded question so a reopened panel does not make
      // the user answer everything again.
      const ungraded = event.questions.findIndex((question) => !isGraded(question));
      const allGraded = ungraded === -1;
      return {
        step: allGraded ? 'graded' : 'answering',
        questions: event.questions,
        current: allGraded ? Math.max(0, event.questions.length - 1) : ungraded,
        spoken: '',
        report: null,
        error: null,
      };
    }

    case 'transcript': {
      if (state.step !== 'answering') return state;
      if (event.channel !== Channel.User || !event.isFinal) return state;
      const text = event.text.trim();
      if (text === '') return state;
      return { ...state, spoken: state.spoken === '' ? text : `${state.spoken} ${text}` };
    }

    case 'submit':
      // Nothing said yet, nothing to grade.
      if (state.step !== 'answering' || state.spoken.trim() === '') return state;
      return { ...state, step: 'grading', error: null };

    case 'graded':
      return {
        ...state,
        step: 'graded',
        questions: state.questions.map((question) =>
          question.position === event.question.position ? event.question : question,
        ),
      };

    case 'next':
      if (state.step !== 'graded') return state;
      // The last question hands over to the report, which the caller fetches.
      if (isLastQuestion(state)) return { ...state, step: 'loading', spoken: '' };
      return { ...state, step: 'answering', current: state.current + 1, spoken: '', error: null };

    case 'report':
      return { ...state, step: 'report', questions: event.report.questions, report: event.report };

    case 'error':
      // Stay where the user can retry: back on the question if there is one.
      return {
        ...state,
        step: state.questions.length === 0 ? 'idle' : 'answering',
        error: event.message,
      };

    case 'reset':
      return NO_PRACTICE;

    default:
      return state;
  }
}
