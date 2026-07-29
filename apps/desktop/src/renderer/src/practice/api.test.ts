import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchReport, gradeAnswer, startPractice } from './api';

const SESSION = '6f1c9a2e-7b3d-4c8e-9f10-2a5b6c7d8e90';

const ok = (body: unknown): Response =>
  ({ ok: true, json: () => Promise.resolve(body) }) as unknown as Response;

const failed = (status: number, text: string): Response =>
  ({ ok: false, status, text: () => Promise.resolve(text) }) as unknown as Response;

let fetchMock: ReturnType<typeof vi.fn>;

/** The single argument list the last call was made with. */
const lastCall = (): [string, RequestInit] => fetchMock.mock.calls[0] as [string, RequestInit];

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('practice api', () => {
  it('posts to the session to generate the question set', async () => {
    fetchMock.mockResolvedValue(ok({ questions: [{ position: 0, question: 'Why us?' }] }));

    const questions = await startPractice('tok', SESSION);

    const [url, init] = lastCall();
    expect(url).toContain(`/v1/practice/${SESSION}`);
    expect(init.method).toBe('POST');
    expect(questions[0]?.question).toBe('Why us?');
  });

  it('sends the bearer token', async () => {
    fetchMock.mockResolvedValue(ok({ questions: [] }));

    await startPractice('tok', SESSION);

    expect(lastCall()[1].headers).toMatchObject({ Authorization: 'Bearer tok' });
  });

  it('posts the spoken answer to the question position', async () => {
    fetchMock.mockResolvedValue(ok({ position: 2, structure: 3 }));

    await gradeAnswer('tok', SESSION, 2, 'it was fine I guess');

    const [url, init] = lastCall();
    expect(url).toContain(`/v1/practice/${SESSION}/2`);
    expect(init.body).toBe(JSON.stringify({ answer: 'it was fine I guess' }));
  });

  it('gets the report', async () => {
    fetchMock.mockResolvedValue(ok({ questions: [], themes: ['no numbers'], summary: 'x' }));

    const report = await fetchReport('tok', SESSION);

    expect(lastCall()[0]).toContain(`/v1/practice/${SESSION}/report`);
    expect(report.themes).toEqual(['no numbers']);
  });

  it('throws the status and body so the panel can show it', async () => {
    // A missing job description arrives this way, and the message is the whole
    // point — it tells the user to go and add one.
    fetchMock.mockResolvedValue(failed(409, 'Add a job description in Settings'));

    await expect(startPractice('tok', SESSION)).rejects.toThrow(
      '409 Add a job description in Settings',
    );
  });
});
