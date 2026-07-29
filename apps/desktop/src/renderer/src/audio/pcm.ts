import { AUDIO_CHANNELS, AUDIO_FRAME_MS, AUDIO_SAMPLE_RATE } from '@vaderai/protocol';

/** Samples per channel in one 100 ms frame: 1600. */
export const FRAME_SAMPLES = (AUDIO_SAMPLE_RATE * AUDIO_FRAME_MS) / 1000;

const WAV_HEADER_BYTES = 44;

/**
 * ch0 (system audio) and ch1 (mic) → one interleaved PCM16 frame of
 * AUDIO_FRAME_BYTES. Both inputs must hold exactly one frame; a mismatch means
 * the worklet and the protocol have drifted apart, which is worth failing on
 * rather than silently shipping misaligned audio to the STT provider.
 */
export function interleavePcm16(ch0: Float32Array, ch1: Float32Array): Int16Array {
  if (ch0.length !== FRAME_SAMPLES || ch1.length !== FRAME_SAMPLES) {
    throw new Error(
      `expected ${FRAME_SAMPLES} samples per channel, got ${ch0.length} and ${ch1.length}`,
    );
  }

  const out = new Int16Array(FRAME_SAMPLES * AUDIO_CHANNELS);
  for (let i = 0; i < FRAME_SAMPLES; i += 1) {
    out[i * 2] = toPcm16(ch0[i] ?? 0);
    out[i * 2 + 1] = toPcm16(ch1[i] ?? 0);
  }
  return out;
}

function toPcm16(sample: number): number {
  const clamped = Math.max(-1, Math.min(1, sample));
  // -1 maps to -32768 and +1 to 32767 — the ranges are asymmetric.
  return Math.round(clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff);
}

/** Wraps PCM16 frames in a 44-byte canonical WAV header. Debug utility only. */
export function encodeWav(frames: readonly Int16Array[]): Uint8Array {
  const samples = frames.reduce((total, frame) => total + frame.length, 0);
  const dataBytes = samples * 2;
  const out = new Uint8Array(WAV_HEADER_BYTES + dataBytes);
  const view = new DataView(out.buffer);
  const byteRate = AUDIO_SAMPLE_RATE * AUDIO_CHANNELS * 2;

  writeAscii(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataBytes, true);
  writeAscii(view, 8, 'WAVE');
  writeAscii(view, 12, 'fmt ');
  view.setUint32(16, 16, true); // PCM fmt chunk size
  view.setUint16(20, 1, true); // PCM, uncompressed
  view.setUint16(22, AUDIO_CHANNELS, true);
  view.setUint32(24, AUDIO_SAMPLE_RATE, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, AUDIO_CHANNELS * 2, true); // block align
  view.setUint16(34, 16, true); // bits per sample
  writeAscii(view, 36, 'data');
  view.setUint32(40, dataBytes, true);

  let offset = WAV_HEADER_BYTES;
  for (const frame of frames) {
    out.set(new Uint8Array(frame.buffer, frame.byteOffset, frame.byteLength), offset);
    offset += frame.byteLength;
  }
  return out;
}

function writeAscii(view: DataView, offset: number, text: string): void {
  for (let i = 0; i < text.length; i += 1) view.setUint8(offset + i, text.charCodeAt(i));
}

/** Rolling window of the most recent frames, so a dump captures what just happened. */
export class FrameBuffer {
  private frames: Int16Array[] = [];

  constructor(private readonly capacity: number) {}

  push(frame: Int16Array): void {
    this.frames.push(frame);
    if (this.frames.length > this.capacity)
      this.frames.splice(0, this.frames.length - this.capacity);
  }

  snapshot(): Int16Array[] {
    return [...this.frames];
  }

  clear(): void {
    this.frames = [];
  }

  get size(): number {
    return this.frames.length;
  }
}
