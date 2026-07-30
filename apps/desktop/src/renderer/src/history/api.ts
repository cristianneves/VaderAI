import { serverHttpUrl } from '../auth/supabase';
import type { SessionDetail, SessionSummary } from './history';

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
