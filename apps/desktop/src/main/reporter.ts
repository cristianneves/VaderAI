import { app, crashReporter, dialog, ipcMain } from 'electron';
import { join } from 'node:path';
import { ErrorLog } from './error-log';

/**
 * Crash and error reporting, entirely local.
 *
 * Nothing is uploaded: a tool that sits on top of a job interview and listens to
 * both sides of the call is the last thing that should be posting stack traces —
 * which routinely carry message text — to a third party. `uploadToServer: false`
 * still gets us native minidumps on disk, which is what a renderer or GPU crash
 * leaves behind that a JavaScript handler never sees.
 */
export function startReporting(): ErrorLog {
  const log = new ErrorLog(join(app.getPath('userData'), 'logs', 'vaderai.log'));

  crashReporter.start({ submitURL: '', uploadToServer: false, compress: true });

  // These two are the difference between "the overlay vanished" and knowing why.
  process.on('uncaughtException', (error) => log.append('main:uncaught', error));
  process.on('unhandledRejection', (reason) => log.append('main:unhandled-rejection', reason));

  app.on('render-process-gone', (_event, _contents, details) => {
    log.append('renderer:gone', `${details.reason} (exit ${details.exitCode})`);
    // The UI is gone at this point, so the log path has to reach the user some
    // other way. 'clean-exit' is a normal quit, not a crash.
    if (details.reason !== 'clean-exit') showCrashNotice(log);
  });

  app.on('child-process-gone', (_event, details) => {
    log.append('child:gone', `${details.type}: ${details.reason}`);
  });

  ipcMain.on('app:report-error', (_event, message: string) => log.append('renderer', message));
  ipcMain.handle('app:log-path', () => log.file);

  return log;
}

function showCrashNotice(log: ErrorLog): void {
  // Unparented for the same reason the export dialog is: the overlay is
  // `focusable: false`, and a dialog modal to it can end up stuck behind an
  // always-on-top window.
  void dialog.showMessageBox({
    type: 'error',
    title: 'VaderAI stopped unexpectedly',
    message: 'The overlay window crashed.',
    detail: `Restart the app to carry on. Details were written to:\n${log.file}`,
  });
}
