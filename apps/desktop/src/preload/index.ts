import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';
import { PROTOCOL_VERSION } from '@vaderai/protocol';
import type { CaptureProtection, OverlayAction } from '../shared/overlay';

// The only channel between renderer and main. Grows in Phase 2 (audio).
// Nothing here exposes Node or ipcRenderer directly.
const api = {
  protocolVersion: PROTOCOL_VERSION,
  platform: process.platform,
  getCaptureProtection: (): Promise<CaptureProtection> =>
    ipcRenderer.invoke('overlay:capture-protection'),
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
