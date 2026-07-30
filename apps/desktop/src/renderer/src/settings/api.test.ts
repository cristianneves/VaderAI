import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchPreferences, saveLanguage } from './api';

const ok = (body: unknown): Response =>
  ({ ok: true, json: () => Promise.resolve(body) }) as unknown as Response;

const failed = (status: number, text: string): Response =>
  ({ ok: false, status, text: () => Promise.resolve(text) }) as unknown as Response;

let fetchMock: ReturnType<typeof vi.fn>;

const lastCall = (): [string, RequestInit] => fetchMock.mock.calls[0] as [string, RequestInit];

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('preferences api', () => {
  it('reads the current language and the available list', async () => {
    fetchMock.mockResolvedValue(
      ok({ language: 'pt-BR', languages: [{ code: 'pt-BR', label: 'Português (Brasil)' }] }),
    );

    const preferences = await fetchPreferences('tok');

    expect(lastCall()[0]).toContain('/v1/preferences');
    expect(preferences.language).toBe('pt-BR');
    expect(preferences.languages[0]?.label).toBe('Português (Brasil)');
  });

  it('sends the bearer token', async () => {
    fetchMock.mockResolvedValue(ok({ language: 'en', languages: [] }));

    await fetchPreferences('tok');

    expect(lastCall()[1].headers).toMatchObject({ Authorization: 'Bearer tok' });
  });

  it('puts a language change', async () => {
    fetchMock.mockResolvedValue(ok(null));

    await saveLanguage('tok', 'ja');

    const [url, init] = lastCall();
    expect(url).toContain('/v1/preferences');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ language: 'ja' });
  });

  it('surfaces a rejected language rather than swallowing it', async () => {
    fetchMock.mockResolvedValue(failed(400, 'unsupported language: klingon'));

    await expect(saveLanguage('tok', 'klingon')).rejects.toThrow('unsupported language');
  });

  it('surfaces an unauthorized read', async () => {
    fetchMock.mockResolvedValue(failed(401, ''));

    await expect(fetchPreferences('stale')).rejects.toThrow('401');
  });
});
