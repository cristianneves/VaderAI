import { app, BrowserWindow, globalShortcut, ipcMain } from 'electron';
import { release } from 'node:os';
import { registerHotkeys } from './hotkeys';
import { createOverlayWindow, moveOverlay, toggleOverlay } from './overlay-window';
import { checkCaptureProtection } from './windows-support';
import type { OverlayAction } from '../shared/overlay';

function handleAction(window: BrowserWindow, action: OverlayAction): void {
  switch (action.type) {
    case 'toggle':
      toggleOverlay(window);
      break;
    case 'move':
      moveOverlay(window, action.dx, action.dy);
      break;
    default:
      // ask / screenshot / clear are renderer concerns; they get real handlers
      // in Phase 4.
      window.webContents.send('overlay:action', action);
  }
}

void app.whenReady().then(() => {
  const capture = checkCaptureProtection(process.platform, release());
  if (!capture.supported) console.warn(`[overlay] ${capture.warning}`);

  const window = createOverlayWindow();
  ipcMain.handle('overlay:capture-protection', () => capture);

  const refused = registerHotkeys(globalShortcut, (action) => handleAction(window, action));
  if (refused.length > 0) {
    console.warn(`[overlay] hotkeys already owned by another app: ${refused.join(', ')}`);
  }
});

app.on('will-quit', () => globalShortcut.unregisterAll());

app.on('window-all-closed', () => app.quit());
