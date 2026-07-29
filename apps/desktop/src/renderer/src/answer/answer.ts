import type { ServerMessage } from '@vaderai/protocol';

export interface AnswerState {
  /** The answer currently being rendered, or null before the first one. */
  id: string | null;
  text: string;
  streaming: boolean;
}

export const NO_ANSWER: AnswerState = { id: null, text: '', streaming: false };

/**
 * Accumulates a streamed answer.
 *
 * <p>Deltas are matched against the current answer id. That is what makes
 * cancel-in-flight look right on screen: when a new question replaces one still
 * streaming, the server starts a new id and any straggling delta from the old
 * answer is dropped instead of being appended to the new one.
 */
export function applyAnswer(state: AnswerState, message: ServerMessage): AnswerState {
  switch (message.type) {
    case 'answer_start':
      return { id: message.answerId, text: '', streaming: true };
    case 'answer_delta':
      if (message.answerId !== state.id) return state;
      return { ...state, text: state.text + message.text };
    case 'answer_end':
      if (message.answerId !== state.id) return state;
      return { ...state, streaming: false };
    default:
      return state;
  }
}
