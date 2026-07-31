import { useCallback, useEffect, useRef, type RefObject } from 'react';

/**
 * How far from the bottom still counts as "at the bottom". A few pixels of
 * slack, because sub-pixel layout means an element scrolled fully down rarely
 * lands on an exact zero.
 */
export const PIN_TOLERANCE_PX = 24;

export interface Scrollable {
  readonly scrollTop: number;
  readonly clientHeight: number;
  readonly scrollHeight: number;
}

export function isPinnedToBottom(element: Scrollable): boolean {
  return element.scrollHeight - element.scrollTop - element.clientHeight <= PIN_TOLERANCE_PX;
}

/**
 * Follows new content, but only while the reader has not scrolled away.
 *
 * Whether to follow is decided from the *last scroll event*, not from the
 * element's state after the update: appending a line grows `scrollHeight`, so
 * an element that was at the bottom a moment ago no longer looks like it is.
 * Reading someone's earlier answer while the interviewer keeps talking is the
 * case this protects.
 */
export function useAutoScroll<T extends HTMLElement>(
  dependency: unknown,
): { ref: RefObject<T>; onScroll: () => void } {
  const ref = useRef<T>(null);
  const pinned = useRef(true);

  const onScroll = useCallback(() => {
    if (ref.current !== null) pinned.current = isPinnedToBottom(ref.current);
  }, []);

  useEffect(() => {
    if (ref.current !== null && pinned.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [dependency]);

  return { ref, onScroll };
}
