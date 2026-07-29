import { AUDIO_SAMPLE_RATE } from '@vaderai/protocol';
import { interleavePcm16 } from './pcm';

const WORKLET_URL = 'pcm-worklet.js';
const PROCESSOR_NAME = 'pcm-frame-processor';
/** Default-device switches arrive as a burst of events; restart once they settle. */
const RESTART_DEBOUNCE_MS = 500;

export type CaptureState = 'idle' | 'starting' | 'running' | 'error';

/**
 * Loopback audio is only granted alongside a video source, but we want audio
 * only — a live video track burns GPU for nothing.
 */
export function stripVideoTracks(stream: MediaStream): void {
  for (const track of stream.getVideoTracks()) {
    track.stop();
    stream.removeTrack(track);
  }
}

/**
 * One 16 kHz 2-channel PCM16 stream: channel 0 is system audio, channel 1 is
 * the mic. Keeping them separate is what makes speaker attribution exact
 * downstream — nothing here may flatten them to mono.
 */
export class AudioCapture {
  private context: AudioContext | null = null;
  private streams: MediaStream[] = [];
  private restartTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly onFrame: (frame: Int16Array) => void,
    private readonly onState: (state: CaptureState, detail?: string) => void,
  ) {}

  async start(): Promise<void> {
    this.stop();
    this.onState('starting');

    try {
      const system = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true });
      stripVideoTracks(system);

      const mic = await navigator.mediaDevices.getUserMedia({
        // Echo cancellation would subtract the interviewer's voice out of the
        // mic channel, which is exactly the signal we are trying to keep apart.
        audio: { echoCancellation: false, noiseSuppression: true },
      });
      this.streams = [system, mic];

      // Asking for a 16 kHz context makes Chromium resample both sources for
      // us, so the worklet only has to frame what it is handed.
      const context = new AudioContext({ sampleRate: AUDIO_SAMPLE_RATE });
      this.context = context;
      await context.audioWorklet.addModule(WORKLET_URL);

      const merger = context.createChannelMerger(2);
      context.createMediaStreamSource(system).connect(merger, 0, 0);
      context.createMediaStreamSource(mic).connect(merger, 0, 1);

      const node = new AudioWorkletNode(context, PROCESSOR_NAME, {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        channelCount: 2,
        channelCountMode: 'explicit',
        // 'discrete' keeps ch0/ch1 as they are; 'speakers' would treat them as
        // L/R and remix them.
        channelInterpretation: 'discrete',
      });
      node.port.onmessage = (event: MessageEvent<{ ch0: Float32Array; ch1: Float32Array }>) => {
        this.onFrame(interleavePcm16(event.data.ch0, event.data.ch1));
      };
      merger.connect(node);

      // Chromium only pulls a worklet that reaches the destination; gain 0
      // keeps the meeting audio from being played back.
      const silence = context.createGain();
      silence.gain.value = 0;
      node.connect(silence).connect(context.destination);

      for (const stream of this.streams) {
        for (const track of stream.getAudioTracks()) {
          track.onended = () => this.scheduleRestart();
        }
      }
      navigator.mediaDevices.addEventListener('devicechange', this.onDeviceChange);

      this.onState('running');
    } catch (error) {
      this.stop();
      this.onState('error', error instanceof Error ? error.message : String(error));
    }
  }

  stop(): void {
    navigator.mediaDevices.removeEventListener('devicechange', this.onDeviceChange);
    if (this.restartTimer !== null) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
    for (const stream of this.streams) {
      for (const track of stream.getTracks()) track.stop();
    }
    this.streams = [];
    void this.context?.close();
    this.context = null;
    this.onState('idle');
  }

  private readonly onDeviceChange = (): void => this.scheduleRestart();

  private scheduleRestart(): void {
    if (this.restartTimer !== null) clearTimeout(this.restartTimer);
    this.restartTimer = setTimeout(() => void this.start(), RESTART_DEBOUNCE_MS);
  }
}
