import { desktopCapturer } from 'electron';
import type { ScreenshotCapture } from '../shared/overlay';

/**
 * 1080p is the useful ceiling: enough to read code or a slide, a fraction of
 * the visual tokens a native 4K grab would cost. `thumbnailSize` does the
 * downsampling in the compositor, so nothing full-size is ever allocated.
 */
export const SCREENSHOT_SIZE = { width: 1920, height: 1080 } as const;

/**
 * The overlay itself is excluded from this capture for the same reason it is
 * excluded from a screen share — content protection applies to every capture
 * path, including ours.
 */
export async function captureScreen(): Promise<ScreenshotCapture | null> {
  const [screen] = await desktopCapturer.getSources({
    types: ['screen'],
    thumbnailSize: SCREENSHOT_SIZE,
  });
  if (screen === undefined || screen.thumbnail.isEmpty()) return null;

  return { mimeType: 'image/png', dataBase64: screen.thumbnail.toPNG().toString('base64') };
}
