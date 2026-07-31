import { describe, expect, it } from 'vitest';
import { isPinnedToBottom, PIN_TOLERANCE_PX } from './auto-scroll';

describe('isPinnedToBottom', () => {
  const pane = (
    scrollTop: number,
  ): { scrollTop: number; clientHeight: number; scrollHeight: number } => ({
    scrollTop,
    clientHeight: 200,
    scrollHeight: 1000,
  });

  it('is pinned when scrolled fully down', () => {
    expect(isPinnedToBottom(pane(800))).toBe(true);
  });

  it('is pinned within the slack, because sub-pixel layout rarely lands on zero', () => {
    expect(isPinnedToBottom(pane(800 - PIN_TOLERANCE_PX))).toBe(true);
  });

  it('is not pinned once the reader has scrolled away', () => {
    expect(isPinnedToBottom(pane(800 - PIN_TOLERANCE_PX - 1))).toBe(false);
  });

  it('is not pinned at the top of a long transcript', () => {
    expect(isPinnedToBottom(pane(0))).toBe(false);
  });

  it('is pinned when the content does not overflow at all', () => {
    expect(isPinnedToBottom({ scrollTop: 0, clientHeight: 200, scrollHeight: 200 })).toBe(true);
  });
});
