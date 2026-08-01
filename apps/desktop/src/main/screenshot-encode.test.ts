import { MAX_SCREENSHOT_BASE64_CHARS } from '@vaderai/protocol';
import type { NativeImage } from 'electron';
import { describe, expect, it, vi } from 'vitest';
import { encodeShot, SCREENSHOT_JPEG_QUALITY, SCREENSHOT_SIZE } from './screenshot-encode';

/** Only the two members encodeShot touches; NativeImage has dozens. */
const image = (options: { empty?: boolean; bytes?: number }): NativeImage =>
  ({
    isEmpty: () => options.empty ?? false,
    toJPEG: vi.fn(() => Buffer.alloc(options.bytes ?? 1024, 1)),
  }) as unknown as NativeImage;

describe('encodeShot', () => {
  it('encodes a normal screen as JPEG at the configured quality', () => {
    const jpeg = image({ bytes: 100_000 });
    const result = encodeShot(jpeg);

    expect(result).toEqual({
      ok: true,
      shot: { mimeType: 'image/jpeg', dataBase64: Buffer.alloc(100_000, 1).toString('base64') },
    });
    expect(jpeg.toJPEG).toHaveBeenCalledWith(SCREENSHOT_JPEG_QUALITY);
  });

  it('accepts an image that lands exactly on the ceiling', () => {
    // base64 is 4 chars per 3 bytes, so this is the largest image that fits.
    const result = encodeShot(image({ bytes: (MAX_SCREENSHOT_BASE64_CHARS / 4) * 3 }));

    expect(result.ok).toBe(true);
  });

  it('refuses a screen too detailed to fit rather than letting it close the socket', () => {
    const result = encodeShot(image({ bytes: (MAX_SCREENSHOT_BASE64_CHARS / 4) * 3 + 1 }));

    expect(result.ok).toBe(false);
    expect(result.ok ? '' : result.reason).not.toBe('');
  });

  it('refuses an empty capture', () => {
    const result = encodeShot(image({ empty: true }));

    expect(result.ok).toBe(false);
  });

  it('captures at 720p — a silent return to 1080p would double the vision tokens', () => {
    expect(SCREENSHOT_SIZE).toEqual({ width: 1280, height: 720 });
  });
});
