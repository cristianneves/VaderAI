import { serverHttpUrl } from '../auth/supabase';
import type { SessionDetail, SessionRecap, SessionSummary } from './history';

async function call(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  const response = await fetch(`${serverHttpUrl}/v1/sessions${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`${response.status} ${await response.text()}`);
  return response;
}

export const fetchSessions = async (token: string): Promise<SessionSummary[]> =>
  (await (await call(token, '')).json()) as SessionSummary[];

export const fetchSession = async (token: string, sessionId: string): Promise<SessionDetail> =>
  (await (await call(token, `/${sessionId}`)).json()) as SessionDetail;

/** The server cascades to turns, answers, and practice questions. */
export async function deleteSession(token: string, sessionId: string): Promise<void> {
  await call(token, `/${sessionId}`, { method: 'DELETE' });
}

/**
 * The recap. Costs a model call the first time and a select afterwards, so it
 * is fetched on demand rather than with the session detail.
 */
export const fetchSummary = async (token: string, sessionId: string): Promise<SessionRecap> =>
  (await (await call(token, `/${sessionId}/summary`)).json()) as SessionRecap;
