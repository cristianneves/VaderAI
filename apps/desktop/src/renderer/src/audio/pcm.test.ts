import { AUDIO_CHANNELS, AUDIO_FRAME_BYTES, AUDIO_SAMPLE_RATE } from '@vaderai/protocol';
import { describe, expect, it } from 'vitest';
import { encodeWav, FRAME_SAMPLES, FrameBuffer, interleavePcm16 } from './pcm';

const frame = (fill = 0): Float32Array => new Float32Array(FRAME_SAMPLES).fill(fill);

describe('interleavePcm16', () => {
  it('produces exactly one protocol frame', () => {
    const pcm = interleavePcm16(frame(), frame());
    expect(pcm.byteLength).toBe(AUDIO_FRAME_BYTES);
    expect(pcm.length).toBe(FRAME_SAMPLES * AUDIO_CHANNELS);
  });

  it('interleaves system audio into even slots and mic into odd slots', () => {
    const pcm = interleavePcm16(frame(1), frame(-1));
    expect([pcm[0], pcm[1], pcm[2], pcm[3]]).toEqual([32767, -32768, 32767, -32768]);
  });

  it('clamps samples beyond ±1 instead of wrapping', () => {
    const pcm = interleavePcm16(frame(4), frame(-4));
    expect(pcm[0]).toBe(32767);
    expect(pcm[1]).toBe(-32768);
  });

  it('rejects channels of different lengths', () => {
    expect(() => interleavePcm16(frame(), new Float32Array(FRAME_SAMPLES - 1))).toThrow(
      /samples per channel/,
    );
  });

  it('rejects a block that is not one frame', () => {
    const short = new Float32Array(128);
    expect(() => interleavePcm16(short, short)).toThrow(/samples per channel/);
  });
});

describe('encodeWav', () => {
  const header = (bytes: Uint8Array): DataView =>
    new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const ascii = (bytes: Uint8Array, at: number, length: number): string =>
    String.fromCharCode(...bytes.slice(at, at + length));

  it('writes a canonical 16 kHz stereo PCM16 header', () => {
    const pcm = interleavePcm16(frame(), frame());
    const wav = encodeWav([pcm]);
    const view = header(wav);

    expect(ascii(wav, 0, 4)).toBe('RIFF');
    expect(ascii(wav, 8, 4)).toBe('WAVE');
    expect(ascii(wav, 12, 4)).toBe('fmt ');
    expect(view.getUint16(20, true)).toBe(1); // uncompressed PCM
    expect(view.getUint16(22, true)).toBe(AUDIO_CHANNELS);
    expect(view.getUint32(24, true)).toBe(AUDIO_SAMPLE_RATE);
    expect(view.getUint32(28, true)).toBe(AUDIO_SAMPLE_RATE * AUDIO_CHANNELS * 2); // byte rate
    expect(view.getUint16(32, true)).toBe(AUDIO_CHANNELS * 2); // block align
    expect(view.getUint16(34, true)).toBe(16); // bits per sample
    expect(ascii(wav, 36, 4)).toBe('data');
  });

  it('sizes the RIFF and data chunks to the payload', () => {
    const wav = encodeWav([interleavePcm16(frame(), frame()), interleavePcm16(frame(), frame())]);
    const view = header(wav);

    expect(wav.byteLength).toBe(44 + AUDIO_FRAME_BYTES * 2);
    expect(view.getUint32(40, true)).toBe(AUDIO_FRAME_BYTES * 2);
    expect(view.getUint32(4, true)).toBe(36 + AUDIO_FRAME_BYTES * 2);
  });

  it('preserves sample order across frame boundaries', () => {
    const first = interleavePcm16(frame(1), frame(1));
    const second = interleavePcm16(frame(-1), frame(-1));
    const wav = encodeWav([first, second]);
    const view = header(wav);

    expect(view.getInt16(44, true)).toBe(32767);
    expect(view.getInt16(44 + AUDIO_FRAME_BYTES, true)).toBe(-32768);
  });

  it('emits a header-only file for no frames', () => {
    const wav = encodeWav([]);
    expect(wav.byteLength).toBe(44);
    expect(header(wav).getUint32(40, true)).toBe(0);
  });
});

describe('FrameBuffer', () => {
  const numbered = (n: number): Int16Array => Int16Array.of(n);

  it('keeps frames in arrival order', () => {
    const buffer = new FrameBuffer(3);
    buffer.push(numbered(1));
    buffer.push(numbered(2));
    expect(buffer.snapshot().map((f) => f[0])).toEqual([1, 2]);
    expect(buffer.size).toBe(2);
  });

  it('drops the oldest frames once past capacity', () => {
    const buffer = new FrameBuffer(2);
    for (const n of [1, 2, 3, 4]) buffer.push(numbered(n));
    expect(buffer.snapshot().map((f) => f[0])).toEqual([3, 4]);
    expect(buffer.size).toBe(2);
  });

  it('returns a copy that later pushes do not mutate', () => {
    const buffer = new FrameBuffer(2);
    buffer.push(numbered(1));
    const taken = buffer.snapshot();
    buffer.push(numbered(2));
    expect(taken).toHaveLength(1);
  });

  it('empties on clear', () => {
    const buffer = new FrameBuffer(2);
    buffer.push(numbered(1));
    buffer.clear();
    expect(buffer.size).toBe(0);
    expect(buffer.snapshot()).toEqual([]);
  });
});
