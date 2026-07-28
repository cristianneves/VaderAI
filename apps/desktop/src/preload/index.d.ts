import type { VaderApi } from './index';

declare global {
  interface Window {
    vader: VaderApi;
  }
}

export {};
