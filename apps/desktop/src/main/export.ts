import { app, dialog, ipcMain } from 'electron';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';

/**
 * Writes an exported session to wherever the user chooses.
 *
 * The dialog is deliberately **not** parented to the overlay window. The overlay
 * is `focusable: false` so it never steals focus from the meeting, and a dialog
 * made modal to a window that cannot take focus is how you end up with one that
 * hangs or hides behind an always-on-top window. Unparented, it gets its own
 * focusable OS window — which is also the only way the user can type a filename.
 */
export function registerExportHandler(): void {
  ipcMain.handle('session:export-markdown', async (_event, name: string, markdown: string) => {
    const { canceled, filePath } = await dialog.showSaveDialog({
      title: 'Export session',
      defaultPath: join(app.getPath('downloads'), name),
      filters: [{ name: 'Markdown', extensions: ['md'] }],
    });
    if (canceled || !filePath) return null;

    await writeFile(filePath, markdown, 'utf8');
    return filePath;
  });
}
