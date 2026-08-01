/**
 * Types crossing the main → preload → renderer boundary. They live here rather
 * than in `src/main` because the renderer is a separate TS project and cannot
 * import from it. The wire format to the backend lives in `@vaderai/protocol`.
 */

export type OverlayAction =
  | { readonly type: 'toggle' }
  | { readonly type: 'ask' }
  | { readonly type: 'compose' }
  | { readonly type: 'screenshot' }
  | { readonly type: 'clear' }
  | { readonly type: 'move'; readonly dx: number; readonly dy: number };

export type CaptureProtection = { supported: true } | { supported: false; warning: string };

/** A downsampled screen grab, ready to go straight into a protocol message. */
export interface ScreenshotCapture {
  mimeType: 'image/jpeg';
  dataBase64: string;
}

/**
 * Why a result rather than `ScreenshotCapture | null`: a capture can fail for
 * two reasons the user can act on differently — there is no screen, or the
 * screen is too detailed to fit under the protocol ceiling — and null collapses
 * both into the overlay doing nothing at all.
 */
export type ShotResult =
  | { readonly ok: true; readonly shot: ScreenshotCapture }
  | { readonly ok: false; readonly reason: string };
