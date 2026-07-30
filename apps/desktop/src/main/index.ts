import { app, BrowserWindow, dialog, globalShortcut, ipcMain } from 'electron';
import { writeFile } from 'node:fs/promises';
import { release } from 'node:os';
import { join } from 'node:path';
import { registerDisplayMediaHandler } from './display-media';
import { registerExportHandler } from './export';
import { registerHotkeys } from './hotkeys';
import { createOverlayWindow, moveOverlay, toggleOverlay } from './overlay-window';
import { startReporting } from './reporter';
import { captureScreen } from './screenshot';
import { captureProtectionNotice, checkCaptureProtection } from './windows-support';
import type { OverlayAction } from '../shared/overlay';

// Before anything else, so a failure during startup is still recorded.
const errorLog = startReporting();

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
  if (!capture.supported) {
    console.warn(`[overlay] ${capture.warning}`);
    errorLog.append('startup:capture-protection', capture.warning);
  }

  registerDisplayMediaHandler();
  registerExportHandler();

  const window = createOverlayWindow();
  ipcMain.handle('overlay:capture-protection', () => capture);

  // The header pill carries this too, but a pill on a transparent overlay is
  // easy to miss right up until the moment it matters. Unparented for the same
  // reason the export dialog is — the overlay cannot take focus.
  const notice = captureProtectionNotice(capture);
  if (notice !== null) {
    void dialog.showMessageBox({ type: 'warning', buttons: ['Got it'], ...notice });
  }

  ipcMain.handle('overlay:screenshot', () => captureScreen());

  // Phase 2 debug utility: the renderer hands over a finished WAV, we only
  // put it somewhere the user can open it.
  ipcMain.handle('audio:dump-wav', async (_event, bytes: Uint8Array) => {
    const file = join(app.getPath('downloads'), 'vaderai-capture.wav');
    await writeFile(file, bytes);
    return file;
  });

  const refused = registerHotkeys(globalShortcut, (action) => handleAction(window, action));
  if (refused.length > 0) {
    console.warn(`[overlay] hotkeys already owned by another app: ${refused.join(', ')}`);
  }
});

app.on('will-quit', () => globalShortcut.unregisterAll());

app.on('window-all-closed', () => app.quit());
