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
