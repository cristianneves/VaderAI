import { useEffect, useRef, useState } from 'react';
import { deleteKnowledge, fetchKnowledge, saveKnowledge, uploadKnowledgeFile } from './api';
import {
  contentOf,
  EMPTY_VIEW,
  formatTokens,
  isOverBudget,
  KNOWLEDGE_KINDS,
  type KnowledgeKind,
  type KnowledgeView,
} from './knowledge';

interface Props {
  accessToken: string;
  onClose: () => void;
}

export function Settings({ accessToken, onClose }: Props): React.JSX.Element {
  const [view, setView] = useState<KnowledgeView>(EMPTY_VIEW);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputs = useRef<Record<string, HTMLInputElement | null>>({});

  async function reload(): Promise<void> {
    try {
      const next = await fetchKnowledge(accessToken);
      setView(next);
      setDrafts(Object.fromEntries(next.documents.map((doc) => [doc.kind, doc.content])));
      setError(null);
    } catch (failed) {
      setError(failed instanceof Error ? failed.message : String(failed));
    }
  }

  useEffect(() => {
    void reload();
  }, []);

  async function run(action: () => Promise<void>): Promise<void> {
    setBusy(true);
    try {
      await action();
      await reload();
    } catch (failed) {
      setError(failed instanceof Error ? failed.message : String(failed));
    }
    setBusy(false);
  }

  const draftFor = (kind: KnowledgeKind): string => drafts[kind] ?? contentOf(view, kind);

  return (
    <section className="pane settings">
      <header className="settings-head">
        <h2>Background</h2>
        <span className={`pill ${isOverBudget(view) ? 'warn' : 'ok'}`}>{formatTokens(view)}</span>
        <button onClick={onClose}>Done</button>
      </header>

      <p className="empty">
        This goes in every answer&apos;s cached prefix — it is what makes an answer cite your actual
        work instead of generalities. It is read when a session starts, so reconnect after editing.
      </p>

      {isOverBudget(view) && (
        <p className="empty warn-text">
          Over {view.ceiling.toLocaleString()} tokens. Trim it — past this point the prefix costs
          more than it adds.
        </p>
      )}

      {KNOWLEDGE_KINDS.map(({ kind, label, hint }) => (
        <div key={kind} className="knowledge-slot">
          <label htmlFor={`kb-${kind}`}>
            {label} <span className="meta">{hint}</span>
          </label>
          <textarea
            id={`kb-${kind}`}
            rows={4}
            value={draftFor(kind)}
            placeholder={`Paste your ${label.toLowerCase()}, or upload a file`}
            onChange={(event) => setDrafts({ ...drafts, [kind]: event.target.value })}
          />
          <div className="row">
            <button
              disabled={busy}
              onClick={() => void run(() => saveKnowledge(accessToken, kind, draftFor(kind)))}
            >
              Save
            </button>
            <button disabled={busy} onClick={() => fileInputs.current[kind]?.click()}>
              Upload file
            </button>
            <button
              disabled={busy || contentOf(view, kind) === ''}
              onClick={() => void run(() => deleteKnowledge(accessToken, kind))}
            >
              Clear
            </button>
            <input
              ref={(element) => {
                fileInputs.current[kind] = element;
              }}
              type="file"
              accept=".pdf,.docx,.txt,.md"
              hidden
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = '';
                if (file) void run(() => uploadKnowledgeFile(accessToken, kind, file));
              }}
            />
          </div>
        </div>
      ))}

      {error !== null && <p className="empty warn-text">{error}</p>}
    </section>
  );
}
