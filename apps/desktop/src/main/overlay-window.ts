import { BrowserWindow } from 'electron';
import { join } from 'node:path';

export function createOverlayWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 460,
    height: 620,
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
