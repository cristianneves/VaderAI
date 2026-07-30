import { appendFileSync, mkdirSync, renameSync, statSync } from 'node:fs';
import { dirname } from 'node:path';

/**
 * Past this the log is rotated to a single `.1` sibling. One generation is
 * enough: what matters is the crash that just happened, and an overlay that
 * quietly eats disk is worse than one that forgets last month.
 */
export const MAX_LOG_BYTES = 512 * 1024;

/** Renders one entry. Kept separate from the writing so it can be asserted on. */
export function formatEntry(when: Date, scope: string, error: unknown): string {
  const detail =
    error instanceof Error
      ? (error.stack ?? `${error.name}: ${error.message}`)
      : typeof error === 'string'
        ? error
        : safeStringify(error);
  // Indent continuation lines so a multi-line stack cannot be mistaken for
  // several entries when the file is read back.
  return `[${when.toISOString()}] ${scope}: ${detail.replace(/\n/g, '\n    ')}\n`;
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    // A circular or otherwise unserialisable value must not take down the very
    // handler that exists to record failures.
    return String(value);
  }
}

/** Append-only error log with single-generation rotation. */
export class ErrorLog {
  constructor(readonly file: string) {}

  /**
   * Writes one entry.
   *
   * Synchronous on purpose: this is called from `uncaughtException` handlers,
   * where the process can exit before an async write ever reaches the disk.
   */
  append(scope: string, error: unknown, now: Date = new Date()): void {
    try {
      mkdirSync(dirname(this.file), { recursive: true });
      this.rotateIfLarge();
      appendFileSync(this.file, formatEntry(now, scope, error), 'utf8');
    } catch {
      // Logging must never be the reason the app dies. If the disk is full or
      // the path is unwritable there is nowhere left to report that anyway.
    }
  }

  private rotateIfLarge(): void {
    try {
      if (statSync(this.file).size < MAX_LOG_BYTES) return;
    } catch {
      return; // No file yet — nothing to rotate.
    }
    renameSync(this.file, `${this.file}.1`);
  }
}
