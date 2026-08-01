import { Menu, Tray, type BrowserWindow } from 'electron';

/**
 * The overlay sets `skipTaskbar: true`, so once it is hidden there is nothing
 * on screen and nothing in the taskbar to bring it back — only `Ctrl+\``, and
 * only if another app has not taken that accelerator. The tray is the way back
 * that does not depend on a hotkey being available, and the only way to quit
 * without Task Manager.
 */
export function createTray(icon: string, window: BrowserWindow, quit: () => void): Tray {
  const tray = new Tray(icon);
  tray.setToolTip('VaderAI');
  tray.setContextMenu(
    Menu.buildFromTemplate([
      // showInactive rather than show: bringing the overlay back must not pull
      // focus off the meeting, which is the whole point of focusable: false.
      { label: 'Show overlay', click: () => window.showInactive() },
      { label: 'Hide overlay', click: () => window.hide() },
      { type: 'separator' },
      { label: 'Quit VaderAI', click: quit },
    ]),
  );
  tray.on('click', () => (window.isVisible() ? window.hide() : window.showInactive()));
  return tray;
}
