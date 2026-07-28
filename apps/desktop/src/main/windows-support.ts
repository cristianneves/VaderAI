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
