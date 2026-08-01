import { authedFetch } from './http';

export interface Usage {
  readonly remaining: number;
  readonly limit: number;
}

/**
 * Below this the count is worth showing. Above it, a running tally of something
 * nobody is near is just another number on a small overlay — the point is a
 * warning before the refusal, not a scoreboard.
 */
export const LOW_USAGE_THRESHOLD = 20;

export const shouldWarn = (usage: Usage | null): usage is Usage =>
  usage !== null && usage.remaining <= LOW_USAGE_THRESHOLD;

export const fetchUsage = async (token: string): Promise<Usage> =>
  (await authedFetch('/v1/usage', token)).json() as Promise<Usage>;
