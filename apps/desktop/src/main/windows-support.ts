import type { CaptureProtection } from '../shared/overlay';

/**
 * Windows 10 version 2004 (build 19041) is the first build where
 * `WDA_EXCLUDEFROMCAPTURE` actually removes a window from a capture. On older
 * builds `setContentProtection(true)` still succeeds but the overlay is drawn
 * as a BLACK rectangle in the shared view — worse than not hiding it at all,
 * and silent unless we check for it.
 */
export const MIN_WINDOWS_BUILD = 19041;

/** `release` is the `os.release()` string, e.g. "10.0.26200" on Windows. */
export function checkCaptureProtection(platform: string, release: string): CaptureProtection {
  if (platform !== 'win32') {
    return {
      supported: false,
      warning: `Capture protection is Windows-only (got platform "${platform}"). The overlay will be visible in screen shares.`,
    };
  }

  const build = Number(release.split('.')[2]);
  if (!Number.isInteger(build)) {
    return {
      supported: false,
      warning: `Could not read a Windows build number from "${release}". Capture protection is unverified.`,
    };
  }

  if (build < MIN_WINDOWS_BUILD) {
    return {
      supported: false,
      warning: `Windows build ${build} is older than ${MIN_WINDOWS_BUILD} (Windows 10 version 2004). The overlay will appear as a black rectangle in screen shares instead of vanishing.`,
    };
  }

  return { supported: true };
}

/** A startup dialog's contents. Shape matches Electron's `showMessageBox`. */
export interface CaptureNotice {
  title: string;
  message: string;
  detail: string;
}

/**
 * What to tell the user at startup when the overlay cannot be hidden, or null
 * when there is nothing to say.
 *
 * The pill in the header already carries the warning, but a pill on a
 * transparent overlay is exactly the thing someone misses right up until they
 * share their screen in a real interview. The point of this one is that it says
 * what to *do*, not just what is wrong.
 */
export function captureProtectionNotice(capture: CaptureProtection): CaptureNotice | null {
  if (capture.supported) return null;

  return {
    title: 'VaderAI cannot hide itself on this machine',
    message: 'The overlay will be visible to anyone you share your screen with.',
    detail: `${capture.warning}\n\nUntil this is resolved, share a single window rather than your entire screen — a window share never contains the overlay. Updating to Windows 10 version 2004 (build ${MIN_WINDOWS_BUILD}) or newer restores the guarantee.`,
  };
}
