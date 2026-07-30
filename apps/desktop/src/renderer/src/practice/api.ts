import { serverHttpUrl } from '../auth/supabase';
import type { PracticeQuestion, PracticeReport } from './practice';

interface QuestionSet {
  questions: PracticeQuestion[];
}

async function call(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  const response = await fetch(`${serverHttpUrl}/v1/practice${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  return response;
}

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
