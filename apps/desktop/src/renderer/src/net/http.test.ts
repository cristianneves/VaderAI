import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authedFetch } from './http';

const failed = (status: number, body: string): Response =>
  ({ ok: false, status, text: () => Promise.resolve(body) }) as unknown as Response;

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('authedFetch', () => {
  it('sends the bearer token and returns a successful response untouched', async () => {
    const response = { ok: true } as Response;
    fetchMock.mockResolvedValue(response);

    await expect(authedFetch('/v1/sessions', 'tok')).resolves.toBe(response);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/v1/sessions');
    expect(init.headers).toMatchObject({ Authorization: 'Bearer tok' });
  });

  it('keeps the caller headers alongside the token', async () => {
    fetchMock.mockResolvedValue({ ok: true } as Response);

    await authedFetch('/v1/preferences', 'tok', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
    });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.headers).toMatchObject({
      'Content-Type': 'application/json',
      Authorization: 'Bearer tok',
    });
  });

  it('reads the sentence out of an RFC 9457 body instead of showing the JSON', async () => {
    fetchMock.mockResolvedValue(
      failed(
        415,
        JSON.stringify({
          type: 'about:blank',
          title: 'Unsupported Media Type',
          status: 415,
          detail: 'That file type cannot be read.',
        }),
      ),
    );

    await expect(authedFetch('/v1/knowledge/resume/file', 'tok')).rejects.toThrow(
      '415 That file type cannot be read.',
    );
  });

  it('falls back to the raw body when the server sent plain text', async () => {
    fetchMock.mockResolvedValue(failed(409, 'add a job description first'));

    await expect(authedFetch('/v1/practice/x', 'tok')).rejects.toThrow(
      '409 add a job description first',
    );
  });

  it('falls back rather than throwing when a JSON body has no usable detail', async () => {
    fetchMock.mockResolvedValue(failed(500, JSON.stringify({ status: 500, detail: '' })));

    await expect(authedFetch('/v1/sessions', 'tok')).rejects.toThrow('500 {"status":500');
  });

  it('reports the status alone when the body is empty', async () => {
    fetchMock.mockResolvedValue(failed(401, ''));

    await expect(authedFetch('/v1/sessions', 'stale')).rejects.toThrow('401');
  });
});
