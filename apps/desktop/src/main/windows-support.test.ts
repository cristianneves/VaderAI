import { describe, expect, it } from 'vitest';
import { checkCaptureProtection, MIN_WINDOWS_BUILD } from './windows-support';

describe('checkCaptureProtection', () => {
  it('supports a current Windows 11 build', () => {
    expect(checkCaptureProtection('win32', '10.0.26200')).toEqual({ supported: true });
  });

  it('supports exactly build 19041 (Windows 10 2004)', () => {
    expect(checkCaptureProtection('win32', `10.0.${MIN_WINDOWS_BUILD}`)).toEqual({
      supported: true,
    });
  });

  it('rejects the build just below 2004', () => {
    const result = checkCaptureProtection('win32', `10.0.${MIN_WINDOWS_BUILD - 1}`);
    expect(result.supported).toBe(false);
    expect(result).toHaveProperty('warning', expect.stringContaining('black rectangle'));
  });

  it('rejects non-Windows platforms', () => {
    const result = checkCaptureProtection('darwin', '24.0.0');
    expect(result.supported).toBe(false);
    expect(result).toHaveProperty('warning', expect.stringContaining('Windows-only'));
  });

  it('rejects an unparseable release string', () => {
    const result = checkCaptureProtection('win32', '10.0');
    expect(result.supported).toBe(false);
    expect(result).toHaveProperty('warning', expect.stringContaining('unverified'));
  });
});
