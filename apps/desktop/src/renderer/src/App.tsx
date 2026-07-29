import { AUDIO_FRAME_MS } from '@vaderai/protocol';
import { useEffect, useRef, useState } from 'react';
import type { CaptureProtection, OverlayAction } from '../../shared/overlay';
import { AudioCapture, type CaptureState } from './audio/capture';
import { encodeWav, FrameBuffer } from './audio/pcm';

const DUMP_SECONDS = 10;
const FRAMES_PER_SECOND = 1000 / AUDIO_FRAME_MS;

export function App(): React.JSX.Element {
  const [capture, setCapture] = useState<CaptureProtection | null>(null);
  const [lastAction, setLastAction] = useState<OverlayAction['type'] | null>(null);
  const [audioState, setAudioState] = useState<CaptureState>('idle');
  const [audioError, setAudioError] = useState<string | null>(null);
  const [buffered, setBuffered] = useState(0);
  const [dumpPath, setDumpPath] = useState<string | null>(null);

  const frames = useRef(new FrameBuffer(DUMP_SECONDS * FRAMES_PER_SECOND));
  const audio = useRef<AudioCapture | null>(null);
  audio.current ??= new AudioCapture(
    (frame) => {
      frames.current.push(frame);
      setBuffered(frames.current.size);
    },
    (state, detail) => {
      setAudioState(state);
      setAudioError(detail ?? null);
    },
  );

  useEffect(() => {
    void window.vader.getCaptureProtection().then(setCapture);
    return window.vader.onOverlayAction((action) => {
      setLastAction(action.type === 'clear' ? null : action.type);
    });
  }, []);

  useEffect(() => () => audio.current?.stop(), []);

  const listening = audioState === 'starting' || audioState === 'running';

  async function dump(): Promise<void> {
    setDumpPath(await window.vader.dumpWav(encodeWav(frames.current.snapshot())));
  }

  return (
    <div className="overlay">
      <header className="handle">
        <span className="title">VaderAI</span>
        <span
          className={`pill ${capture?.supported ? 'ok' : 'warn'}`}
          title={capture?.supported ? undefined : capture?.warning}
        >
          {capture === null
            ? 'checking…'
            : capture.supported
              ? 'hidden from capture'
              : 'visible in capture'}
        </span>
      </header>

      <section className="pane transcript">
        <h2>Transcript</h2>
        <p className="empty">
          {listening
            ? 'Capturing audio — transcription lands in Phase 3.'
            : 'No audio yet — press Start listening.'}
        </p>
      </section>

      <section className="pane answer">
        <h2>Answer</h2>
        <p className="empty">
          {lastAction === null
            ? 'Ctrl+Enter to ask · Ctrl+H to ask about the screen'
            : `“${lastAction}” received — answers land in Phase 4.`}
        </p>
      </section>

      <footer className="controls">
        <button onClick={() => (listening ? audio.current?.stop() : void audio.current?.start())}>
          {listening ? 'Stop' : 'Start listening'}
        </button>
        <button onClick={() => void dump()} disabled={buffered === 0}>
          Dump {DUMP_SECONDS}s WAV
        </button>
        <span className="meta">
          {audioState === 'error'
            ? (audioError ?? 'capture failed')
            : `${(buffered / FRAMES_PER_SECOND).toFixed(1)}s buffered`}
        </span>
      </footer>

      {dumpPath !== null && <p className="meta path">{dumpPath}</p>}
    </div>
  );
}
