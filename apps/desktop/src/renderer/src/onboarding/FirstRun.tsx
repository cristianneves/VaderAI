import { useState } from 'react';
import { requestMicrophone, type FirstRunStep, type MicPermission } from './first-run';

interface Props {
  step: Exclude<FirstRunStep, 'signin'>;
  micPermission: MicPermission;
  onMicPermission: (permission: MicPermission) => void;
  onOpenBackground: () => void;
  onSkip: () => void;
}

/**
 * The post-sign-in half of the first-run flow. Sign-in itself is a different
 * screen — `App` swaps the whole overlay for it when there is no session.
 */
export function FirstRun({
  step,
  micPermission,
  onMicPermission,
  onOpenBackground,
  onSkip,
}: Props): React.JSX.Element {
  const [busy, setBusy] = useState(false);

  async function grant(): Promise<void> {
    setBusy(true);
    onMicPermission(await requestMicrophone());
    setBusy(false);
  }

  return (
    <section className="pane first-run">
      <h2>{step === 'microphone' ? 'Let VaderAI hear you' : 'Add your background'}</h2>

      {step === 'microphone' ? (
        <>
          <p className="empty">
            Your microphone is one of the two channels — it is how the app knows which half of the
            conversation is yours. System audio needs no permission.
          </p>
          {micPermission === 'denied' && (
            <p className="empty warn-text">
              Windows is refusing the microphone. Allow it under Settings › Privacy &amp; security ›
              Microphone, then try again.
            </p>
          )}
          <div className="row">
            <button disabled={busy} onClick={() => void grant()}>
              {busy ? 'Asking…' : 'Allow microphone'}
            </button>
            <button className="link" onClick={onSkip}>
              Skip for now
            </button>
          </div>
        </>
      ) : (
        <>
          <p className="empty">
            Answers are only as specific as what you give them. A résumé and the job description are
            the difference between citing a project of yours by name and generic advice.
          </p>
          <div className="row">
            <button onClick={onOpenBackground}>Add background</button>
            <button className="link" onClick={onSkip}>
              Skip for now
            </button>
          </div>
        </>
      )}
    </section>
  );
}
