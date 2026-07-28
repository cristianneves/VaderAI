import { AUDIO_FRAME_BYTES, AUDIO_SAMPLE_RATE } from '@vaderai/protocol';

export function App(): React.JSX.Element {
  return (
    <main className="shell">
      <h1>VaderAI</h1>
      <p className="tag">Phase 0 — scaffold</p>
      <dl>
        <dt>protocol</dt>
        <dd>v{window.vader.protocolVersion}</dd>
        <dt>platform</dt>
        <dd>{window.vader.platform}</dd>
        <dt>audio</dt>
        <dd>
          {AUDIO_SAMPLE_RATE / 1000} kHz &middot; {AUDIO_FRAME_BYTES} B/frame
        </dd>
      </dl>
    </main>
  );
}
