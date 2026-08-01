import { describe, expect, it } from 'vitest';
import { LOW_USAGE_THRESHOLD, shouldWarn } from './usage';

describe('shouldWarn', () => {
  it('stays quiet when the cap is nowhere near', () => {
    expect(shouldWarn({ remaining: 120, limit: 120 })).toBe(false);
    expect(shouldWarn({ remaining: LOW_USAGE_THRESHOLD + 1, limit: 120 })).toBe(false);
  });

  it('warns from the threshold down', () => {
    expect(shouldWarn({ remaining: LOW_USAGE_THRESHOLD, limit: 120 })).toBe(true);
    expect(shouldWarn({ remaining: 3, limit: 120 })).toBe(true);
  });

  it('warns loudest at none left, rather than going quiet again', () => {
    expect(shouldWarn({ remaining: 0, limit: 120 })).toBe(true);
  });

  it('says nothing when usage is unknown — a failed read is not a warning', () => {
    expect(shouldWarn(null)).toBe(false);
  });
});
