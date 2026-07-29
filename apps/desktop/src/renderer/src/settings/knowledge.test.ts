import { describe, expect, it } from 'vitest';
import {
  contentOf,
  EMPTY_VIEW,
  formatTokens,
  isOverBudget,
  KNOWLEDGE_KINDS,
  type KnowledgeView,
} from './knowledge';

const view = (over: Partial<KnowledgeView> = {}): KnowledgeView => ({ ...EMPTY_VIEW, ...over });

describe('contentOf', () => {
  it('returns the content for a kind that is present', () => {
    const state = view({ documents: [{ kind: 'resume', title: 'cv.pdf', content: 'ten years' }] });

    expect(contentOf(state, 'resume')).toBe('ten years');
  });

  it('returns an empty string for a kind that is not set', () => {
    expect(contentOf(EMPTY_VIEW, 'notes')).toBe('');
  });
});

describe('isOverBudget', () => {
  it('is false at the ceiling', () => {
    expect(isOverBudget(view({ tokens: 8000, ceiling: 8000 }))).toBe(false);
  });

  it('is true past the ceiling', () => {
    expect(isOverBudget(view({ tokens: 8001, ceiling: 8000 }))).toBe(true);
  });
});

describe('formatTokens', () => {
  // The thousands separator follows the user's locale, so these assert the
  // parts this function actually decides rather than pinning a separator.
  it('marks an estimated count with a tilde', () => {
    expect(formatTokens(view({ tokens: 1234, estimated: true }))).toMatch(/^~\d\D?\d+ tokens$/);
  });

  it('shows a measured count without one', () => {
    // The distinction matters: one is the model's tokenizer, the other a guess.
    expect(formatTokens(view({ tokens: 1234, estimated: false }))).not.toContain('~');
  });

  it('groups thousands using the local convention', () => {
    expect(formatTokens(view({ tokens: 1234, estimated: false }))).toBe(
      `${(1234).toLocaleString()} tokens`,
    );
  });
});

describe('KNOWLEDGE_KINDS', () => {
  it('lists the three slots in the order the server assembles them', () => {
    expect(KNOWLEDGE_KINDS.map((entry) => entry.kind)).toEqual([
      'resume',
      'job_description',
      'notes',
    ]);
  });
});
