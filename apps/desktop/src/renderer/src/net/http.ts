import { serverHttpUrl } from '../auth/supabase';

/**
 * One authenticated call against the backend. The four feature APIs went
 * through four copies of this before; sharing it means the error path is read
 * in one place, which is what the server's move to RFC 9457 needed.
 */
export async function authedFetch(
  path: string,
  token: string,
  init: RequestInit = {},
): Promise<Response> {
  const response = await fetch(`${serverHttpUrl}${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`${response.status} ${await humanMessage(response)}`);
  return response;
}

/**
 * RFC 9457 puts the sentence written for a person in `detail`. Anything else —
 * a proxy's HTML, a bare string, an empty body — falls back to the raw text,
 * which is what every message used to be.
 */
async function humanMessage(response: Response): Promise<string> {
  const body = await response.text();
  try {
    const detail: unknown = (JSON.parse(body) as { detail?: unknown }).detail;
    return typeof detail === 'string' && detail !== '' ? detail : body;
  } catch {
    return body;
  }
}
