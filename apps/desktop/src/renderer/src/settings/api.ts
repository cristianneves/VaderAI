import { authedFetch } from '../net/http';
import type { KnowledgeKind, KnowledgeView } from './knowledge';

const call = (token: string, path: string, init: RequestInit = {}): Promise<Response> =>
  authedFetch(`/v1/knowledge${path}`, token, init);

export const fetchKnowledge = async (token: string): Promise<KnowledgeView> =>
  (await call(token, '')).json() as Promise<KnowledgeView>;

export async function saveKnowledge(
  token: string,
  kind: KnowledgeKind,
  content: string,
  title: string | null = null,
): Promise<void> {
  await call(token, `/${kind}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, content }),
  });
}

/** The server extracts the text — PDF and DOCX parsing stays on one platform. */
export async function uploadKnowledgeFile(
  token: string,
  kind: KnowledgeKind,
  file: File,
): Promise<void> {
  const body = new FormData();
  body.append('file', file);
  await call(token, `/${kind}/file`, { method: 'POST', body });
}

export async function deleteKnowledge(token: string, kind: KnowledgeKind): Promise<void> {
  await call(token, `/${kind}`, { method: 'DELETE' });
}

/**
 * The language list comes from the server rather than being duplicated here —
 * it has to match what the speech model accepts, and two copies would drift.
 */
export interface PreferencesView {
  language: string;
  languages: { code: string; label: string }[];
}

const preferences = (token: string, init: RequestInit = {}): Promise<Response> =>
  authedFetch('/v1/preferences', token, init);

export const fetchPreferences = async (token: string): Promise<PreferencesView> =>
  (await preferences(token)).json() as Promise<PreferencesView>;

export async function saveLanguage(token: string, language: string): Promise<void> {
  await preferences(token, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ language }),
  });
}
