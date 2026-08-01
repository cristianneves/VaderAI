import { desktopCapturer } from 'electron';
import { encodeShot, SCREENSHOT_SIZE } from './screenshot-encode';
import type { ShotResult } from '../shared/overlay';

export { SCREENSHOT_SIZE };

/**
 * The overlay itself is excluded from this capture for the same reason it is
 * excluded from a screen share — content protection applies to every capture
 * path, including ours.
 *
 * <p>`thumbnailSize` does the downsampling in the compositor, so nothing
 * full-size is ever allocated. Everything after the grab lives in
 * `screenshot-encode`, which has no runtime dependency on electron and so can
 * be tested.
 */
export async function captureScreen(): Promise<ShotResult> {
  const [screen] = await desktopCapturer.getSources({
    types: ['screen'],
    thumbnailSize: SCREENSHOT_SIZE,
  });
  if (screen === undefined) return { ok: false, reason: 'no screen was available to capture' };

  return encodeShot(screen.thumbnail);
}
