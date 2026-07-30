import { AUDIO_FRAME_MS } from '@vaderai/protocol';
import type { Session } from '@supabase/supabase-js';
import { useEffect, useRef, useState } from 'react';
import type { CaptureProtection } from '../../shared/overlay';
import { applyAnswer, NO_ANSWER, type AnswerState } from './answer/answer';
import { AudioCapture, type CaptureState } from './audio/capture';
import { encodeWav, FrameBuffer } from './audio/pcm';
import { SignIn } from './auth/SignIn';
import { serverWsUrl, supabase } from './auth/supabase';
import { HistoryPanel } from './history/HistoryPanel';
import { SessionSocket, type ConnectionState } from './net/session';
import {
  dismiss,
  isDismissed,
  nextStep,
  readMicPermission,
  type MicPermission,
} from './onboarding/first-run';
import { FirstRun } from './onboarding/FirstRun';
import { applyPractice, NO_PRACTICE, type PracticeState } from './practice/practice';
import { PracticePanel } from './practice/PracticePanel';
import { fetchKnowledge } from './settings/api';
import type { KnowledgeView } from './settings/knowledge';
import { Settings } from './settings/Settings';
import { applyTranscript, speakerOf, type TranscriptLine } from './transcript/log';

const DUMP_SECONDS = 10;
const FRAMES_PER_SECOND = 1000 / AUDIO_FRAME_MS;

/**
 * Which panel is showing. One value rather than a boolean each: with four panels
 * every toggle would otherwise have to remember to close the other three.
 */
type View = 'live' | 'settings' | 'practice' | 'history';

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
  const [view, setView] = useState<View>('live');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [practice, setPractice] = useState<PracticeState>(NO_PRACTICE);
  const [micPermission, setMicPermission] = useState<MicPermission>('unknown');
  const [knowledge, setKnowledge] = useState<KnowledgeView | null>(null);
  const [skipped, setSkipped] = useState(() => isDismissed(localStorage));

  /** Clicking the open panel's button returns to the live view. */
  const toggle = (panel: View): void => setView((current) => (current === panel ? 'live' : panel));

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

  const token = session?.access_token;

  /**
   * Left null on failure rather than falling back to an empty view: a backend
   * that is down should not be reported to the user as "you have no résumé".
   */
  function reloadKnowledge(): void {
    if (token === undefined) return;
    void fetchKnowledge(token)
      .then(setKnowledge)
      .catch(() => undefined);
  }

  useEffect(() => {
    if (token === undefined) return;
    void readMicPermission().then(setMicPermission);
    reloadKnowledge();
  }, [token]);

  const listening = audioState === 'starting' || audioState === 'running';

  const onboarding = nextStep({
    signedIn: session !== null,
    micPermission,
    knowledge,
    dismissed: skipped,
  });

  function skipOnboarding(): void {
    dismiss(localStorage);
    setSkipped(true);
  }

  async function startListening(micOnly = false): Promise<void> {
    const token = session?.access_token;
    if (token !== undefined) {
      socket.current ??= new SessionSocket(serverWsUrl, {
        onMessage: (message) => {
          switch (message.type) {
            case 'ready':
              // Practice mode addresses this session over REST, so the id has to
              // outlive the frame that carried it.
              setSessionId(message.sessionId);
              break;
            case 'transcript':
              setLines((current) => applyTranscript(current, message));
              // In practice mode the spoken answer is the channel-1 transcript;
              // the reducer ignores everything else.
              setPractice((current) =>
                applyPractice(current, {
                  type: 'transcript',
                  channel: message.channel,
                  text: message.text,
                  isFinal: message.isFinal,
                }),
              );
              break;
            default:
              setAnswer((current) => applyAnswer(current, message));
              break;
          }
        },
        onState: setConnection,
      });
      socket.current.connect(token);
    }
    await audio.current?.start({ micOnly });
  }

  function stopListening(): void {
    audio.current?.stop();
    socket.current?.close();
    setConnection('idle');
    setSessionId(null);
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
        <button className="link" onClick={() => toggle('settings')}>
          {view === 'settings' ? 'Close' : 'Background'}
        </button>
        <button className="link" onClick={() => toggle('practice')}>
          {view === 'practice' ? 'Close' : 'Practice'}
        </button>
        <button className="link" onClick={() => toggle('history')}>
          {view === 'history' ? 'Close' : 'History'}
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

      {view === 'settings' ? (
        <Settings
          accessToken={session.access_token}
          onClose={() => {
            setView('live');
            // Re-read so the first-run prompt clears the moment something is saved.
            reloadKnowledge();
          }}
        />
      ) : view === 'practice' ? (
        <PracticePanel
          accessToken={session.access_token}
          sessionId={sessionId}
          state={practice}
          onEvent={(event) => setPractice((current) => applyPractice(current, event))}
          onClose={() => setView('live')}
        />
      ) : view === 'history' ? (
        <HistoryPanel accessToken={session.access_token} onClose={() => setView('live')} />
      ) : onboarding !== null && onboarding !== 'signin' ? (
        <FirstRun
          step={onboarding}
          micPermission={micPermission}
          onMicPermission={setMicPermission}
          onOpenBackground={() => setView('settings')}
          onSkip={skipOnboarding}
        />
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
        <button
          onClick={() => (listening ? stopListening() : void startListening(view === 'practice'))}
        >
          {listening ? 'Stop' : view === 'practice' ? 'Start (mic only)' : 'Start listening'}
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
