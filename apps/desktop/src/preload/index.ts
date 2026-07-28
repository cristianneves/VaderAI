import { contextBridge } from 'electron';
import { PROTOCOL_VERSION } from '@vaderai/protocol';

// The only channel between renderer and main. Grows in Phase 1 (hotkeys) and
// Phase 2 (audio). Nothing here exposes Node or ipcRenderer directly.
const api = {
  protocolVersion: PROTOCOL_VERSION,
  platform: process.platform,
} as const;

contextBridge.exposeInMainWorld('vader', api);

export type VaderApi = typeof api;
