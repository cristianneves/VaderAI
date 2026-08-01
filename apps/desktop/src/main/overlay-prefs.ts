/**
 * Where the overlay sits, how solid it is, and whether clicks pass through it.
 *
 * Kept free of electron so the rules — clamping, defaults, tolerating a file
 * written by an older build — can be tested. The file itself is read and
 * written by `overlay-store.ts`.
 */

export interface Bounds {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

export interface OverlayPrefs {
  /** Null means "let the OS place it", which is what a first run wants. */
  readonly bounds: Bounds | null;
  readonly opacity: number;
  readonly clickThrough: boolean;
}

export const DEFAULT_SIZE = { width: 460, height: 620 } as const;

/** Below this the transcript and answer panes stop being readable at all. */
export const MIN_SIZE = { width: 320, height: 240 } as const;

/**
 * A floor, not a preference. An overlay at 0 opacity is invisible, still on
 * top, and still eating clicks unless click-through happens to be on — which
 * is a window the user cannot find to fix.
 */
export const MIN_OPACITY = 0.3;

export const DEFAULT_PREFS: OverlayPrefs = {
  bounds: null,
  opacity: 1,
  clickThrough: false,
};

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);

export const clampOpacity = (value: number): number => Math.min(1, Math.max(MIN_OPACITY, value));

function parseBounds(raw: unknown): Bounds | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const { x, y, width, height } = raw as Record<string, unknown>;
  if (![x, y, width, height].every(isFiniteNumber)) return null;
  return {
    x: x as number,
    y: y as number,
    width: Math.max(MIN_SIZE.width, width as number),
    height: Math.max(MIN_SIZE.height, height as number),
  };
}

/**
 * Reads whatever is on disk into something usable.
 *
 * Deliberately total: a corrupt or half-written file, or one from a build that
 * did not have these fields, falls back per field rather than throwing. Losing
 * a window position is not worth failing to start over.
 */
export function parsePrefs(raw: unknown): OverlayPrefs {
  if (typeof raw !== 'object' || raw === null) return DEFAULT_PREFS;
  const { bounds, opacity, clickThrough } = raw as Record<string, unknown>;
  return {
    bounds: parseBounds(bounds),
    opacity: isFiniteNumber(opacity) ? clampOpacity(opacity) : DEFAULT_PREFS.opacity,
    clickThrough: typeof clickThrough === 'boolean' ? clickThrough : DEFAULT_PREFS.clickThrough,
  };
}

/**
 * Drops saved bounds that no longer land on a screen.
 *
 * The case this exists for: the overlay was last used on a second monitor,
 * that monitor is gone, and restoring the position faithfully would put the
 * window somewhere the user cannot see or reach. Requires a real overlap
 * rather than a touching edge, so a window flush against the far side of a
 * display it is not on does not count as visible.
 */
export function visibleBounds(bounds: Bounds | null, displays: readonly Bounds[]): Bounds | null {
  if (bounds === null) return null;
  const onScreen = displays.some(
    (area) =>
      bounds.x < area.x + area.width &&
      bounds.x + bounds.width > area.x &&
      bounds.y < area.y + area.height &&
      bounds.y + bounds.height > area.y,
  );
  return onScreen ? bounds : null;
}
