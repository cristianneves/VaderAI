/**
 * Frames the 2-channel graph output into 100 ms blocks and posts them to the
 * renderer, which converts them to interleaved PCM16.
 *
 * Dependency-free and served from `public/` on purpose: an AudioWorklet module
 * is fetched by URL at runtime, so anything imported here would have to resolve
 * both under the dev server and under file:// in the packaged app.
 *
 * FRAME_SAMPLES mirrors AUDIO_SAMPLE_RATE * AUDIO_FRAME_MS in @vaderai/protocol.
 * interleavePcm16() rejects any block of another size, so drift cannot pass
 * silently.
 */
const FRAME_SAMPLES = 1600;

class PcmFrameProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ch0 = new Float32Array(FRAME_SAMPLES);
    this.ch1 = new Float32Array(FRAME_SAMPLES);
    this.filled = 0;
  }

  process(inputs) {
    const input = inputs[0];
    // Fewer than 2 channels means the graph is not delivering the merged
    // stream yet. Emitting anything here would misattribute the speaker.
    if (!input || input.length < 2) return true;

    const [system, mic] = input;
    let offset = 0;
    // A render quantum is 128 frames and 1600 is not a multiple of it, so a
    // quantum can straddle two output frames.
    while (offset < system.length) {
      const take = Math.min(system.length - offset, FRAME_SAMPLES - this.filled);
      this.ch0.set(system.subarray(offset, offset + take), this.filled);
      this.ch1.set(mic.subarray(offset, offset + take), this.filled);
      this.filled += take;
      offset += take;

      if (this.filled === FRAME_SAMPLES) {
        this.port.postMessage({ ch0: this.ch0, ch1: this.ch1 }, [this.ch0.buffer, this.ch1.buffer]);
        this.ch0 = new Float32Array(FRAME_SAMPLES);
        this.ch1 = new Float32Array(FRAME_SAMPLES);
        this.filled = 0;
      }
    }
    return true;
  }
}

registerProcessor('pcm-frame-processor', PcmFrameProcessor);
