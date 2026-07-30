import { fetchReport, gradeAnswer, startPractice } from './api';
import {
  currentQuestion,
  isLastQuestion,
  type PracticeEvent,
  type PracticeQuestion,
  type PracticeState,
} from './practice';

interface Props {
  accessToken: string;
  /** From the socket's `ready` frame; null until the session is connected. */
  sessionId: string | null;
  state: PracticeState;
  onEvent: (event: PracticeEvent) => void;
  onClose: () => void;
}

function Scores({ question }: { question: PracticeQuestion }): React.JSX.Element {
  return (
    <div className="row scores">
      <span className="pill">Structure {question.structure}/5</span>
      <span className="pill">Specificity {question.specificity}/5</span>
      <span className="pill">Relevance {question.relevance}/5</span>
    </div>
  );
}

export function PracticePanel({
  accessToken,
  sessionId,
  state,
  onEvent,
  onClose,
}: Props): React.JSX.Element {
  const question = currentQuestion(state);

  /** Every call funnels through here so one failure path covers them all. */
  async function run(action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (failed) {
      onEvent({
        type: 'error',
        message: failed instanceof Error ? failed.message : String(failed),
      });
    }
  }

  function begin(): void {
    if (sessionId === null) return;
    onEvent({ type: 'start' });
    void run(async () =>
      onEvent({ type: 'questions', questions: await startPractice(accessToken, sessionId) }),
    );
  }

  function submit(): void {
    if (sessionId === null || question === null) return;
    const answer = state.spoken;
    onEvent({ type: 'submit' });
    void run(async () =>
      onEvent({
        type: 'graded',
        question: await gradeAnswer(accessToken, sessionId, question.position, answer),
      }),
    );
  }

  function next(): void {
    if (sessionId === null) return;
    const finishing = isLastQuestion(state);
    onEvent({ type: 'next' });
    if (finishing) {
      void run(async () =>
        onEvent({ type: 'report', report: await fetchReport(accessToken, sessionId) }),
      );
    }
  }

  return (
    <section className="pane practice">
      <header className="settings-head">
        <h2>Practice</h2>
        {state.questions.length > 0 && state.step !== 'report' && (
          <span className="pill">
            {state.current + 1} of {state.questions.length}
          </span>
        )}
        <button onClick={onClose}>Done</button>
      </header>

      {state.step === 'idle' && (
        <>
          <p className="empty">
            A mock interview from your saved job description — five questions, answered out loud.
            Only your mic is recorded.
          </p>
          <div className="row">
            <button disabled={sessionId === null} onClick={begin}>
              Start mock interview
            </button>
          </div>
          {sessionId === null && (
            <p className="empty">Press Start listening first — practice needs a live session.</p>
          )}
        </>
      )}

      {state.step === 'loading' && <p className="empty">Working…</p>}

      {question !== null && (state.step === 'answering' || state.step === 'grading') && (
        <>
          <p className="question">{question.question}</p>
          <p className={state.spoken === '' ? 'empty' : 'answer-text'}>
            {state.spoken === '' ? 'Listening — answer out loud.' : state.spoken}
          </p>
          <div className="row">
            <button disabled={state.step === 'grading' || state.spoken === ''} onClick={submit}>
              {state.step === 'grading' ? 'Grading…' : 'Submit answer'}
            </button>
          </div>
        </>
      )}

      {question !== null && state.step === 'graded' && (
        <>
          <p className="question">{question.question}</p>
          <Scores question={question} />
          <p className="answer-text">{question.feedback}</p>
          <p className="meta">Say it like this</p>
          <p className="answer-text rewrite">{question.rewrite}</p>
          <div className="row">
            <button onClick={next}>{isLastQuestion(state) ? 'See report' : 'Next question'}</button>
          </div>
        </>
      )}

      {state.step === 'report' && state.report !== null && (
        <>
          <p className="meta">Weakest themes</p>
          {state.report.themes.length === 0 ? (
            <p className="empty">Nothing graded in this session.</p>
          ) : (
            <ul className="themes">
              {state.report.themes.map((theme) => (
                <li key={theme}>{theme}</li>
              ))}
            </ul>
          )}
          <p className="answer-text">{state.report.summary}</p>
          {state.report.questions
            .filter((graded) => graded.feedback !== null)
            .map((graded) => (
              <div key={graded.position} className="knowledge-slot">
                <p className="question">{graded.question}</p>
                <Scores question={graded} />
                <p className="answer-text">{graded.feedback}</p>
              </div>
            ))}
          <div className="row">
            <button onClick={() => onEvent({ type: 'reset' })}>Start over</button>
          </div>
        </>
      )}

      {state.error !== null && <p className="empty warn-text">{state.error}</p>}
    </section>
  );
}
