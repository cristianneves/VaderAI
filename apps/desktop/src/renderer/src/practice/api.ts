import { authedFetch } from '../net/http';
import type { PracticeQuestion, PracticeReport } from './practice';

interface QuestionSet {
  questions: PracticeQuestion[];
}

const call = (token: string, path: string, init: RequestInit = {}): Promise<Response> =>
  authedFetch(`/v1/practice${path}`, token, init);

/**
 * Generates the question set from the stored job description. The session id is
 * the one the socket already reported in its `ready` frame — practice reuses the
 * live session rather than opening its own.
 */
export const startPractice = async (
  token: string,
  sessionId: string,
): Promise<PracticeQuestion[]> =>
  ((await (await call(token, `/${sessionId}`, { method: 'POST' })).json()) as QuestionSet)
    .questions;

export const fetchQuestions = async (
  token: string,
  sessionId: string,
): Promise<PracticeQuestion[]> =>
  ((await (await call(token, `/${sessionId}`)).json()) as QuestionSet).questions;

export const gradeAnswer = async (
  token: string,
  sessionId: string,
  position: number,
  answer: string,
): Promise<PracticeQuestion> =>
  (await (
    await call(token, `/${sessionId}/${position}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answer }),
    })
  ).json()) as PracticeQuestion;

export const fetchReport = async (token: string, sessionId: string): Promise<PracticeReport> =>
  (await (await call(token, `/${sessionId}/report`)).json()) as PracticeReport;
