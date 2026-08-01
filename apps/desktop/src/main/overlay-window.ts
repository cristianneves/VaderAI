import { BrowserWindow, screen } from 'electron';
import { join } from 'node:path';
import {
  clampOpacity,
  DEFAULT_SIZE,
  MIN_SIZE,
  visibleBounds,
  type OverlayPrefs,
} from './overlay-prefs';

export function createOverlayWindow(prefs: OverlayPrefs): BrowserWindow {
  // Saved bounds are only honoured if they still land on a screen — see
  // visibleBounds for the case this exists for.
  const restored = visibleBounds(
    prefs.bounds,
    screen.getAllDisplays().map((display) => display.workArea),
  );

  const window = new BrowserWindow({
    ...DEFAULT_SIZE,
    ...(restored ?? {}),
    minWidth: MIN_SIZE.width,
    minHeight: MIN_SIZE.height,
    show: false,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    // Without this the overlay steals focus from the meeting window every time
    // it is shown — the single most visible way to get this window wrong.
    focusable: false,
    webPreferences: {
      preload: join(__dirname, '../preload/index.mjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  // WDA_EXCLUDEFROMCAPTURE on Windows: the compositor omits the window from
  // every capture path, including a meeting app's screen share.
  window.setContentProtection(true);
  // 'screen-saver' is the level that stays above full-screen meeting apps.
  window.setAlwaysOnTop(true, 'screen-saver');
  window.setOpacity(prefs.opacity);
  setClickThrough(window, prefs.clickThrough);

  window.on('ready-to-show', () => window.showInactive());

  const devServerUrl = process.env['ELECTRON_RENDERER_URL'];
  if (devServerUrl) {
    void window.loadURL(devServerUrl);
  } else {
    void window.loadFile(join(__dirname, '../renderer/index.html'));
  }

  return window;
}

export function toggleOverlay(window: BrowserWindow): void {
  if (window.isVisible()) window.hide();
  else window.showInactive();
}

export function moveOverlay(window: BrowserWindow, dx: number, dy: number): void {
  const [x = 0, y = 0] = window.getPosition();
  window.setPosition(x + dx, y + dy);
}

/**
 * Steps the overlay's opacity and returns what it landed on.
 *
 * Clamped rather than wrapped: someone dimming past the floor wants "as faint
 * as it goes", not a jump back to solid.
 */
export function stepOpacity(window: BrowserWindow, delta: number): number {
  const next = clampOpacity(window.getOpacity() + delta);
  window.setOpacity(next);
  return next;
}

/**
 * Lets clicks fall through to the meeting underneath.
 *
 * `forward: true` keeps mouse-move events coming, so hover still works and the
 * window is not simply inert. The escape hatch is a global hotkey — with the
 * overlay ignoring clicks there is no button left to press, so this must never
 * be the only way back.
 */
export function setClickThrough(window: BrowserWindow, ignore: boolean): void {
  window.setIgnoreMouseEvents(ignore, { forward: true });
}

/**
 * Lends the overlay keyboard focus for as long as the ask bar is open.
 *
 * `focusable: false` maps to `WS_EX_NOACTIVATE` on Windows, which is what stops
 * the overlay stealing focus from the meeting — and also what stops a text
 * field ever receiving a keystroke. So focus is borrowed rather than kept:
 * granted when the composer opens, handed straight back when it closes, so the
 * meeting window is active again for everything except the moment the user is
 * deliberately typing at us.
 */
export function setComposing(window: BrowserWindow, composing: boolean): void {
  if (composing) {
    window.setFocusable(true);
    window.focus();
    return;
  }
  // Blur first: dropping focusability while still focused leaves Windows with
  // no active window rather than returning to the meeting.
  window.blur();
  window.setFocusable(false);
}
