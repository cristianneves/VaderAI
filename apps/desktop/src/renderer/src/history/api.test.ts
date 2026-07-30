import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { deleteSession, fetchSession, fetchSessions, fetchSummary } from './api';

const SESSION = '6f1c9a2e-7b3d-4c8e-9f10-2a5b6c7d8e90';

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

describe('history api', () => {
  it('lists sessions', async () => {
    fetchMock.mockResolvedValue(ok([{ id: SESSION, kind: 'live' }]));

    const sessions = await fetchSessions('tok');

    expect(lastCall()[0]).toContain('/v1/sessions');
    expect(sessions[0]?.id).toBe(SESSION);
  });

  it('sends the bearer token', async () => {
    fetchMock.mockResolvedValue(ok([]));

    await fetchSessions('tok');

    expect(lastCall()[1].headers).toMatchObject({ Authorization: 'Bearer tok' });
  });

  it('fetches one session by id', async () => {
    fetchMock.mockResolvedValue(ok({ id: SESSION, turns: [], answers: [] }));

    const detail = await fetchSession('tok', SESSION);

    expect(lastCall()[0]).toContain(`/v1/sessions/${SESSION}`);
    expect(detail.id).toBe(SESSION);
  });

  it('deletes with the DELETE method', async () => {
    fetchMock.mockResolvedValue(ok(null));

    await deleteSession('tok', SESSION);

    const [url, init] = lastCall();
    expect(url).toContain(`/v1/sessions/${SESSION}`);
    expect(init.method).toBe('DELETE');
  });

  it('throws the status and body so the panel can show it', async () => {
    fetchMock.mockResolvedValue(failed(404, 'no session'));

    await expect(fetchSession('tok', SESSION)).rejects.toThrow('404 no session');
  });
});

describe('session recap', () => {
  it('fetches the recap for one session', async () => {
    fetchMock.mockResolvedValue(
      ok({ summary: 'You discussed payments.', keyPoints: ['RDS'], actionItems: [] }),
    );

    const recap = await fetchSummary('tok', SESSION);

    expect(lastCall()[0]).toContain(`/v1/sessions/${SESSION}/summary`);
    expect(recap.summary).toBe('You discussed payments.');
    expect(recap.keyPoints).toEqual(['RDS']);
  });

  it('surfaces a session with nothing to summarise', async () => {
    fetchMock.mockResolvedValue(failed(409, 'Nothing was said in this session'));

    await expect(fetchSummary('tok', SESSION)).rejects.toThrow('Nothing was said');
  });

  it('surfaces another user\u2019s session as not found', async () => {
    fetchMock.mockResolvedValue(failed(404, 'no session'));

    await expect(fetchSummary('tok', SESSION)).rejects.toThrow('404');
  });
});
