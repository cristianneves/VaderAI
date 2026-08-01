import { MAX_SCREENSHOT_BASE64_CHARS } from '@vaderai/protocol';
// Type-only, so this module never pulls electron in at runtime and can be
// tested directly. The `desktopCapturer` call stays in screenshot.ts.
import type { NativeImage } from 'electron';
import type { ShotResult } from '../shared/overlay';

/**
 * 1280x720, down from 1080p. Vision tokens are roughly `width * height / 750`,
 * so this is ~1229 against ~2765 — and because the image sits after the prompt
 * cache breakpoint it is re-billed and re-prefilled on every single screenshot
 * ask. Halving it is a latency change as much as a size one. It is also under
 * Anthropic's ~1568px long-edge threshold, so nothing is downscaled again on
 * their side.
 */
export const SCREENSHOT_SIZE = { width: 1280, height: 720 } as const;

/**
 * JPEG rather than PNG because a PNG screenshot has no size bound that depends
 * on anything we control — a photo or a gradient wallpaper blows past any
 * ceiling — while a JPEG's is set by quality. The cost is ringing around small
 * glyphs; at 720p with a normal editor font, code stays legible. If it ever
 * does not, move this number rather than the resolution.
 */
export const SCREENSHOT_JPEG_QUALITY = 70;

/**
 * Encodes a captured screen for the wire, or explains why it cannot.
 *
 * The size check is the point: exceeding the ceiling means exceeding the
 * container's WebSocket text buffer, which closes the live session with 1009
 * and no error frame. A sentence in the overlay is strictly better than a
 * dropped interview.
 */
export function encodeShot(image: NativeImage): ShotResult {
  if (image.isEmpty()) return { ok: false, reason: 'no screen was available to capture' };

  const dataBase64 = image.toJPEG(SCREENSHOT_JPEG_QUALITY).toString('base64');
  if (dataBase64.length > MAX_SCREENSHOT_BASE64_CHARS) {
    return {
      ok: false,
      reason: 'that screen is too detailed to send — try sharing a smaller window',
    };
  }
  return { ok: true, shot: { mimeType: 'image/jpeg', dataBase64 } };
}
