import { app, BrowserWindow, dialog, globalShortcut, ipcMain } from 'electron';
import { writeFile } from 'node:fs/promises';
import { release } from 'node:os';
import { join } from 'node:path';
import { registerDisplayMediaHandler } from './display-media';
import { registerExportHandler } from './export';
import { registerHotkeys } from './hotkeys';
import type { OverlayPrefs } from './overlay-prefs';
import { OverlayStore } from './overlay-store';
import {
  createOverlayWindow,
  moveOverlay,
  setClickThrough,
  setComposing,
  stepOpacity,
  toggleOverlay,
} from './overlay-window';
import { startReporting } from './reporter';
import { captureScreen } from './screenshot';
import { createTray } from './tray';
import { captureProtectionNotice, checkCaptureProtection } from './windows-support';
import type { OverlayAction } from '../shared/overlay';

// Before anything else, so a failure during startup is still recorded.
const errorLog = startReporting();

/** How long the window has to stay put before its position is written down. */
const SAVE_DEBOUNCE_MS = 400;

function handleAction(window: BrowserWindow, prefs: PrefsHandle, action: OverlayAction): void {
  switch (action.type) {
    case 'toggle':
      toggleOverlay(window);
      break;
    case 'move':
      moveOverlay(window, action.dx, action.dy);
      break;
    case 'opacity':
      prefs.update({ opacity: stepOpacity(window, action.delta) });
      break;
    case 'click-through': {
      const clickThrough = !prefs.current.clickThrough;
      setClickThrough(window, clickThrough);
      prefs.update({ clickThrough });
      // The renderer says so out loud: a window that stopped taking clicks and
      // did not mention it reads as frozen.
      window.webContents.send('overlay:click-through', clickThrough);
      break;
    }
    case 'compose':
      // Focus has to be granted before the renderer mounts the input, or the
      // field takes no keystrokes.
      if (!window.isVisible()) window.showInactive();
      setComposing(window, true);
      window.webContents.send('overlay:action', action);
      break;
    default:
      // ask / screenshot / clear are renderer concerns.
      window.webContents.send('overlay:action', action);
  }
}

/** Holds the live prefs and writes them out, coalescing bursts of movement. */
interface PrefsHandle {
  readonly current: OverlayPrefs;
  update(patch: Partial<OverlayPrefs>): void;
  flush(): void;
}

function trackPrefs(store: OverlayStore, initial: OverlayPrefs): PrefsHandle {
  let current = initial;
  let pending: NodeJS.Timeout | null = null;

  return {
    get current() {
      return current;
    },
    update(patch) {
      current = { ...current, ...patch };
      // A drag fires 'moved' continuously; writing each one would hammer the
      // disk for a value only the last of which matters.
      if (pending !== null) clearTimeout(pending);
      pending = setTimeout(() => store.write(current), SAVE_DEBOUNCE_MS);
    },
    flush() {
      if (pending !== null) clearTimeout(pending);
      pending = null;
      store.write(current);
    },
  };
}

void app.whenReady().then(() => {
  const capture = checkCaptureProtection(process.platform, release());
  if (!capture.supported) {
    console.warn(`[overlay] ${capture.warning}`);
    errorLog.append('startup:capture-protection', capture.warning);
  }

  registerDisplayMediaHandler();
  registerExportHandler();

  const store = new OverlayStore(join(app.getPath('userData'), 'overlay.json'));
  const prefs = trackPrefs(store, store.read());

  const window = createOverlayWindow(prefs.current);
  ipcMain.handle('overlay:capture-protection', () => capture);

  // Both fire per drag step, which is why the write is debounced.
  const remember = (): void => prefs.update({ bounds: window.getBounds() });
  window.on('moved', remember);
  window.on('resized', remember);
  // A quit can beat the debounce; the last position is the one worth keeping.
  window.on('close', () => prefs.flush());

  const tray = createTray(join(__dirname, '../../build/icon.ico'), window, () => app.quit());
  app.on('will-quit', () => tray.destroy());

  // The header pill carries this too, but a pill on a transparent overlay is
  // easy to miss right up until the moment it matters. Unparented for the same
  // reason the export dialog is — the overlay cannot take focus.
  const notice = captureProtectionNotice(capture);
  if (notice !== null) {
    void dialog.showMessageBox({ type: 'warning', buttons: ['Got it'], ...notice });
  }

  ipcMain.handle('overlay:screenshot', () => captureScreen());

  // The renderer hands focus back when the ask bar closes — on submit, on
  // Escape, or on clicking away.
  ipcMain.on('overlay:composing', (_event, composing: boolean) => setComposing(window, composing));

  // Phase 2 debug utility: the renderer hands over a finished WAV, we only
  // put it somewhere the user can open it.
  ipcMain.handle('audio:dump-wav', async (_event, bytes: Uint8Array) => {
    const file = join(app.getPath('downloads'), 'vaderai-capture.wav');
    await writeFile(file, bytes);
    return file;
  });

  const refused = registerHotkeys(globalShortcut, (action) => handleAction(window, prefs, action));
  if (refused.length > 0) {
    console.warn(`[overlay] hotkeys already owned by another app: ${refused.join(', ')}`);
  }
});

app.on('will-quit', () => globalShortcut.unregisterAll());

app.on('window-all-closed', () => app.quit());
