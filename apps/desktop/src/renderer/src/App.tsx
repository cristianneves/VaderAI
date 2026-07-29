import { AUDIO_FRAME_MS } from '@vaderai/protocol';
import type { Session } from '@supabase/supabase-js';
import { useEffect, useRef, useState } from 'react';
import type { CaptureProtection } from '../../shared/overlay';
import { applyAnswer, NO_ANSWER, type AnswerState } from './answer/answer';
import { AudioCapture, type CaptureState } from './audio/capture';
import { encodeWav, FrameBuffer } from './audio/pcm';
import { SignIn } from './auth/SignIn';
import { serverWsUrl, supabase } from './auth/supabase';
import { SessionSocket, type ConnectionState } from './net/session';
import { Settings } from './settings/Settings';
import { applyTranscript, speakerOf, type TranscriptLine } from './transcript/log';

const DUMP_SECONDS = 10;
const FRAMES_PER_SECOND = 1000 / AUDIO_FRAME_MS;

export function App(): React.JSX.Element {
  const [capture, setCapture] = useState<CaptureProtection | null>(null);
  const [audioState, setAudioState] = useState<CaptureState>('idle');
  const [audioError, setAudioError] = useState<string | null>(null);
  const [buffered, setBuffered] = useState(0);
  const [dumpPath, setDumpPath] = useState<string | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [connection, setConnection] = useState<ConnectionState>('idle');
  const [lines, setLines] = useState<TranscriptLine[]>([]);
  const [answer, setAnswer] = useState<AnswerState>(NO_ANSWER);
  const [showSettings, setShowSettings] = useState(false);

  const frames = useRef(new FrameBuffer(DUMP_SECONDS * FRAMES_PER_SECOND));
  const socket = useRef<SessionSocket | null>(null);
  const audio = useRef<AudioCapture | null>(null);
  audio.current ??= new AudioCapture(
    (frame) => {
      frames.current.push(frame);
      socket.current?.sendAudio(frame);
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
      switch (action.type) {
        case 'clear':
          setLines([]);
          setAnswer(NO_ANSWER);
          break;
        case 'ask':
          socket.current?.ask();
          break;
        case 'screenshot':
          void window.vader.captureScreen().then((shot) => {
            if (shot !== null) socket.current?.askAboutScreen(shot);
          });
          break;
        default:
          break;
      }
    });
  }, []);

  useEffect(() => {
    if (supabase === null) return;
    void supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_event, next) => setSession(next));
    return () => data.subscription.unsubscribe();
  }, []);

  useEffect(
    () => () => {
      audio.current?.stop();
      socket.current?.close();
    },
    [],
  );

  const listening = audioState === 'starting' || audioState === 'running';

  async function startListening(): Promise<void> {
    const token = session?.access_token;
    if (token !== undefined) {
      socket.current ??= new SessionSocket(serverWsUrl, {
        onMessage: (message) => {
          if (message.type === 'transcript') {
            setLines((current) => applyTranscript(current, message));
          } else {
            setAnswer((current) => applyAnswer(current, message));
          }
        },
        onState: setConnection,
      });
      socket.current.connect(token);
    }
    await audio.current?.start();
  }

  function stopListening(): void {
    audio.current?.stop();
    socket.current?.close();
    setConnection('idle');
  }

  async function dump(): Promise<void> {
    setDumpPath(await window.vader.dumpWav(encodeWav(frames.current.snapshot())));
  }

  if (session === null) {
    return (
      <div className="overlay">
        <header className="handle">
          <span className="title">VaderAI</span>
        </header>
        <SignIn />
      </div>
    );
  }

  return (
    <div className="overlay">
      <header className="handle">
        <span className="title">VaderAI</span>
        <button className="link" onClick={() => setShowSettings((open) => !open)}>
          {showSettings ? 'Close' : 'Background'}
        </button>
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

      {showSettings ? (
        <Settings accessToken={session.access_token} onClose={() => setShowSettings(false)} />
      ) : (
        <>
          <section className="pane transcript">
            <h2>Transcript</h2>
            {lines.length === 0 ? (
              <p className="empty">
                {listening ? 'Listening…' : 'No audio yet — press Start listening.'}
              </p>
            ) : (
              lines.map((line, index) => (
                <p key={index} className={`line ${line.isFinal ? '' : 'interim'}`}>
                  <span className={`speaker ch${line.channel}`}>{speakerOf(line.channel)}</span>
                  {line.text}
                </p>
              ))
            )}
          </section>

          <section className="pane answer">
            <h2>Answer{answer.streaming && <span className="cursor"> ▍</span>}</h2>
            {answer.text === '' ? (
              <p className="empty">
                {answer.streaming
                  ? 'Thinking…'
                  : 'Ctrl+Enter to ask · Ctrl+H to ask about the screen'}
              </p>
            ) : (
              <p className="answer-text">{answer.text}</p>
            )}
          </section>
        </>
      )}

      <footer className="controls">
        <button onClick={() => (listening ? stopListening() : void startListening())}>
          {listening ? 'Stop' : 'Start listening'}
        </button>
        <button onClick={() => void dump()} disabled={buffered === 0}>
          Dump {DUMP_SECONDS}s WAV
        </button>
        <span className="meta">
          {audioState === 'error'
            ? (audioError ?? 'capture failed')
            : `${connection} · ${(buffered / FRAMES_PER_SECOND).toFixed(1)}s buffered`}
        </span>
      </footer>

      {dumpPath !== null && <p className="meta path">{dumpPath}</p>}
    </div>
  );
}
