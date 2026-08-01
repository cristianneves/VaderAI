import { readFileSync, writeFileSync } from 'node:fs';
import { parsePrefs, type OverlayPrefs } from './overlay-prefs';

/**
 * The overlay's own preferences file, next to the error log in userData.
 *
 * Separate from the knowledge base and language, which live server-side and
 * follow the account: where a window sits belongs to the machine it sits on,
 * not to the person.
 */
export class OverlayStore {
  constructor(readonly file: string) {}

  read(): OverlayPrefs {
    try {
      return parsePrefs(JSON.parse(readFileSync(this.file, 'utf8')));
    } catch {
      // No file yet, or one we cannot read. parsePrefs already decides what
      // every missing field means, so there is nothing else to do here.
      return parsePrefs(null);
    }
  }

  write(prefs: OverlayPrefs): void {
    try {
      writeFileSync(this.file, JSON.stringify(prefs), 'utf8');
    } catch {
      // Losing a window position must never take down the overlay.
    }
  }
}
