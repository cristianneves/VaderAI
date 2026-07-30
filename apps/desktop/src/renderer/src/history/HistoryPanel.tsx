import { useEffect, useState } from 'react';
import { fetchQuestions } from '../practice/api';
import type { PracticeQuestion } from '../practice/practice';
import { deleteSession, fetchSession, fetchSessions } from './api';
import {
  describeSession,
  exportFileName,
  speakerLabel,
  timelineOf,
  type SessionDetail,
  type SessionSummary,
} from './history';
import { toMarkdown } from './markdown';

interface Props {
  accessToken: string;
  onClose: () => void;
}

export function HistoryPanel({ accessToken, onClose }: Props): React.JSX.Element {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [open, setOpen] = useState<SessionSummary | null>(null);
  const [detail, setDetail] = useState<SessionDetail | null>(null);
  const [questions, setQuestions] = useState<PracticeQuestion[]>([]);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [exported, setExported] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** Every call funnels through here so one failure path covers them all. */
  async function run(action: () => Promise<void>): Promise<void> {
    setBusy(true);
    try {
      await action();
      setError(null);
    } catch (failed) {
      setError(failed instanceof Error ? failed.message : String(failed));
    }
    setBusy(false);
  }

  useEffect(() => {
    void run(async () => setSessions(await fetchSessions(accessToken)));
  }, []);

  function review(summary: SessionSummary): void {
    setOpen(summary);
    setExported(null);
    void run(async () => {
      setDetail(await fetchSession(accessToken, summary.id));
      // A practice run keeps its answers on its question rows, and this route
      // costs no model call — unlike the report route.
      setQuestions(
        summary.kind === 'practice' ? await fetchQuestions(accessToken, summary.id) : [],
      );
    });
  }

  function back(): void {
    setOpen(null);
    setDetail(null);
    setQuestions([]);
    setConfirming(null);
  }

  function remove(id: string): void {
    void run(async () => {
      await deleteSession(accessToken, id);
      setSessions(await fetchSessions(accessToken));
      back();
    });
  }

  function exportSession(): void {
    if (open === null || detail === null) return;
    void run(async () => {
      const path = await window.vader.exportMarkdown(
        exportFileName(open),
        toMarkdown(open, detail, questions),
      );
      // Null means the user cancelled the dialog, which is not an error.
      if (path !== null) setExported(path);
    });
  }

  if (open !== null) {
    const timeline = detail === null ? [] : timelineOf(detail);
    return (
      <section className="pane history">
        <header className="settings-head">
          <h2>{open.kind === 'practice' ? 'Practice' : 'Session'}</h2>
          <span className="pill">{describeSession(open)}</span>
          <button onClick={back}>Back</button>
        </header>

        {open.kind === 'practice' ? (
          questions.length === 0 ? (
            <p className="empty">{busy ? 'Loading…' : 'No questions in this session.'}</p>
          ) : (
            questions.map((question) => (
              <div key={question.position} className="knowledge-slot">
                <p className="question">
                  {question.position + 1}. {question.question}
                </p>
                {question.answer === null ? (
                  <p className="empty">Not answered.</p>
                ) : (
                  <>
                    <p className="answer-text">{question.answer}</p>
                    <div className="row scores">
                      <span className="pill">Structure {question.structure}/5</span>
                      <span className="pill">Specificity {question.specificity}/5</span>
                      <span className="pill">Relevance {question.relevance}/5</span>
                    </div>
                    <p className="answer-text">{question.feedback}</p>
                  </>
                )}
              </div>
            ))
          )
        ) : timeline.length === 0 ? (
          <p className="empty">{busy ? 'Loading…' : 'Nothing was captured in this session.'}</p>
        ) : (
          timeline.map((entry, index) => (
            <p key={index} className={`line entry-${entry.kind}`}>
              <span className={`speaker ${entry.kind}`}>
                {speakerLabel(entry.kind)}
                {entry.trigger === 'screenshot' && ' (screen)'}
              </span>
              {entry.text}
            </p>
          ))
        )}

        <div className="row">
          <button disabled={busy || detail === null} onClick={exportSession}>
            Export Markdown
          </button>
          {confirming === open.id ? (
            <>
              <button disabled={busy} onClick={() => remove(open.id)}>
                Delete for good
              </button>
              <button disabled={busy} onClick={() => setConfirming(null)}>
                Keep
              </button>
            </>
          ) : (
            <button disabled={busy} onClick={() => setConfirming(open.id)}>
              Delete
            </button>
          )}
        </div>

        {confirming === open.id && (
          <p className="empty warn-text">
            This removes the transcript, the answers, and any grades with it.
          </p>
        )}
        {exported !== null && <p className="meta path">{exported}</p>}
        {error !== null && <p className="empty warn-text">{error}</p>}
      </section>
    );
  }

  return (
    <section className="pane history">
      <header className="settings-head">
        <h2>History</h2>
        <button onClick={onClose}>Done</button>
      </header>

      {sessions.length === 0 ? (
        <p className="empty">{busy ? 'Loading…' : 'No sessions yet.'}</p>
      ) : (
        sessions.map((summary) => (
          <button key={summary.id} className="session-row" onClick={() => review(summary)}>
            <span className="session-date">
              {new Date(summary.startedAt).toLocaleString()}
              {summary.kind === 'practice' && <span className="pill">practice</span>}
            </span>
            <span className="meta">{describeSession(summary)}</span>
          </button>
        ))
      )}

      {error !== null && <p className="empty warn-text">{error}</p>}
    </section>
  );
}
