import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import { PROTOCOL_VERSION } from '@vaderai/protocol';
import type { CaptureProtection, OverlayAction, ScreenshotCapture } from '../shared/overlay';

// The only channel between renderer and main. Grows in Phase 2 (audio).
// Nothing here exposes Node or ipcRenderer directly.
const api = {
  protocolVersion: PROTOCOL_VERSION,
  platform: process.platform,
  getCaptureProtection: (): Promise<CaptureProtection> =>
    ipcRenderer.invoke('overlay:capture-protection'),
  /** Grabs the screen at 1080p for the Ctrl+H path. Null if no screen is available. */
  captureScreen: (): Promise<ScreenshotCapture | null> => ipcRenderer.invoke('overlay:screenshot'),
  /** Writes a finished WAV to disk and resolves with its path. Debug only. */
  dumpWav: (bytes: Uint8Array): Promise<string> => ipcRenderer.invoke('audio:dump-wav', bytes),
  /** Save-dialog export. Resolves with the chosen path, or null if cancelled. */
  exportMarkdown: (name: string, markdown: string): Promise<string | null> =>
    ipcRenderer.invoke('session:export-markdown', name, markdown),
  /**
   * Records a renderer-side failure in the same local log the main process
   * writes. Fire-and-forget: a reporting call that could itself reject would be
   * one more thing to handle inside an error handler.
   */
  reportError: (message: string): void => ipcRenderer.send('app:report-error', message),
  /** Where that log lives, for telling the user where to look. */
  getLogPath: (): Promise<string> => ipcRenderer.invoke('app:log-path'),
  /** Subscribes to hotkey actions main forwards. Returns an unsubscribe fn. */
  onOverlayAction: (handler: (action: OverlayAction) => void): (() => void) => {
    const listener = (_event: IpcRendererEvent, action: OverlayAction): void => handler(action);
    ipcRenderer.on('overlay:action', listener);
    return () => {
      ipcRenderer.off('overlay:action', listener);
    };
  },
} as const;

contextBridge.exposeInMainWorld('vader', api);

export type VaderApi = typeof api;
