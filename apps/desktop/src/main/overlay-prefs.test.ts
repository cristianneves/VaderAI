import { describe, expect, it } from 'vitest';
import {
  clampOpacity,
  DEFAULT_PREFS,
  MIN_OPACITY,
  MIN_SIZE,
  parsePrefs,
  visibleBounds,
  type Bounds,
} from './overlay-prefs';

const display = (x: number, y: number): Bounds => ({ x, y, width: 1920, height: 1080 });

describe('parsePrefs', () => {
  it('reads a file this build wrote', () => {
    const prefs = parsePrefs({
      bounds: { x: 100, y: 50, width: 500, height: 700 },
      opacity: 0.8,
      clickThrough: true,
    });

    expect(prefs).toEqual({
      bounds: { x: 100, y: 50, width: 500, height: 700 },
      opacity: 0.8,
      clickThrough: true,
    });
  });

  it('falls back per field rather than all-or-nothing', () => {
    // What a build that predates click-through wrote.
    const prefs = parsePrefs({ bounds: { x: 1, y: 2, width: 400, height: 500 } });

    expect(prefs.bounds).not.toBeNull();
    expect(prefs.opacity).toBe(DEFAULT_PREFS.opacity);
    expect(prefs.clickThrough).toBe(false);
  });

  it('survives a corrupt or half-written file instead of failing to start', () => {
    expect(parsePrefs(null)).toEqual(DEFAULT_PREFS);
    expect(parsePrefs('not an object')).toEqual(DEFAULT_PREFS);
    expect(parsePrefs({ bounds: 'nonsense', opacity: 'nonsense' })).toEqual(DEFAULT_PREFS);
  });

  it('drops bounds with a missing or non-numeric field', () => {
    expect(parsePrefs({ bounds: { x: 1, y: 2, width: 400 } }).bounds).toBeNull();
    expect(parsePrefs({ bounds: { x: 1, y: 2, width: 400, height: NaN } }).bounds).toBeNull();
  });

  it('raises a saved size below the readable minimum', () => {
    const prefs = parsePrefs({ bounds: { x: 0, y: 0, width: 10, height: 10 } });

    expect(prefs.bounds).toMatchObject({ width: MIN_SIZE.width, height: MIN_SIZE.height });
  });

  it('never returns an opacity that would hide the window', () => {
    expect(parsePrefs({ opacity: 0 }).opacity).toBe(MIN_OPACITY);
    expect(parsePrefs({ opacity: -5 }).opacity).toBe(MIN_OPACITY);
    expect(parsePrefs({ opacity: 4 }).opacity).toBe(1);
  });
});

describe('clampOpacity', () => {
  it('leaves a usable value alone', () => {
    expect(clampOpacity(0.75)).toBe(0.75);
  });

  it('refuses to make the overlay invisible or more than solid', () => {
    expect(clampOpacity(0)).toBe(MIN_OPACITY);
    expect(clampOpacity(1.5)).toBe(1);
  });
});

describe('visibleBounds', () => {
  it('keeps a position that lands on a screen', () => {
    const bounds = { x: 100, y: 100, width: 460, height: 620 };

    expect(visibleBounds(bounds, [display(0, 0)])).toEqual(bounds);
  });

  it('keeps a position on a second monitor while that monitor is there', () => {
    const bounds = { x: 2000, y: 100, width: 460, height: 620 };

    expect(visibleBounds(bounds, [display(0, 0), display(1920, 0)])).toEqual(bounds);
  });

  it('drops a position on a monitor that has been unplugged', () => {
    const bounds = { x: 2000, y: 100, width: 460, height: 620 };

    expect(visibleBounds(bounds, [display(0, 0)])).toBeNull();
  });

  it('does not count a window merely touching a display edge as visible', () => {
    const bounds = { x: -460, y: 0, width: 460, height: 620 };

    expect(visibleBounds(bounds, [display(0, 0)])).toBeNull();
  });

  it('passes null through — a first run has nothing to restore', () => {
    expect(visibleBounds(null, [display(0, 0)])).toBeNull();
  });
});
