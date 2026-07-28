import { useEffect, useState } from 'react';
import type { CaptureProtection, OverlayAction } from '../../shared/overlay';

export function App(): React.JSX.Element {
  const [capture, setCapture] = useState<CaptureProtection | null>(null);
  const [lastAction, setLastAction] = useState<OverlayAction['type'] | null>(null);

  useEffect(() => {
    void window.vader.getCaptureProtection().then(setCapture);
    return window.vader.onOverlayAction((action) => {
      setLastAction(action.type === 'clear' ? null : action.type);
    });
  }, []);

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
        <p className="empty">No audio yet — capture lands in Phase 2.</p>
      </section>

      <section className="pane answer">
        <h2>Answer</h2>
        <p className="empty">
          {lastAction === null
            ? 'Ctrl+Enter to ask · Ctrl+H to ask about the screen'
            : `“${lastAction}” received — answers land in Phase 4.`}
        </p>
      </section>
    </div>
  );
}
