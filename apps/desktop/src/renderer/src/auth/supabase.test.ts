import { describe, expect, it } from 'vitest';
import { httpUrlFromWs } from './supabase';

describe('httpUrlFromWs', () => {
  it('derives the local dev origin', () => {
    expect(httpUrlFromWs('ws://localhost:8787/v1/session')).toBe('http://localhost:8787');
  });

  it('keeps TLS for a deployed backend — wss becomes https, never http', () => {
    const url = httpUrlFromWs('wss://vaderai-server.fly.dev/v1/session');
    expect(url).toBe('https://vaderai-server.fly.dev');
    expect(url.startsWith('https://')).toBe(true);
  });

  it('leaves a URL without the session path alone apart from the scheme', () => {
    expect(httpUrlFromWs('wss://vaderai-server.fly.dev')).toBe('https://vaderai-server.fly.dev');
  });

  it('only strips /v1/session at the end, not in the middle of a path', () => {
    expect(httpUrlFromWs('wss://example.com/v1/session/extra')).toBe(
      'https://example.com/v1/session/extra',
    );
  });

  it('only rewrites the scheme, not a host that happens to start with ws', () => {
    expect(httpUrlFromWs('wss://ws.example.com/v1/session')).toBe('https://ws.example.com');
  });
});
