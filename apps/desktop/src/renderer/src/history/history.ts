export type SessionKind = 'live' | 'practice';
export type AnswerTrigger = 'auto' | 'manual' | 'screenshot';

export interface SessionSummary {
  id: string;
  kind: SessionKind;
  startedAt: string;
  /** Null when the socket died before the session was closed. */
  endedAt: string | null;
  turns: number;
  answers: number;
  practiceQuestions: number;
}

export interface SessionTurn {
  channel: number;
  content: string;
  createdAt: string;
}

export interface SessionAnswer {
  content: string;
  trigger: AnswerTrigger;
  createdAt: string;
}

export interface SessionDetail {
  id: string;
  kind: SessionKind;
  startedAt: string;
  endedAt: string | null;
  turns: SessionTurn[];
  answers: SessionAnswer[];
}

export type EntryKind = 'interviewer' | 'you' | 'answer';

export interface TimelineEntry {
  kind: EntryKind;
  text: string;
  /** Only set on answers. */
  trigger?: AnswerTrigger;
}

/**
 * Merges the transcript and the answers into one ordered reading.
 *
 * <p>The server returns them as separate lists because they come from separate
 * tables; interleaving by timestamp is what puts each answer after the question
 * that prompted it. Ties keep transcript before answer, since an answer cannot
 * precede the words that triggered it.
 */
export function timelineOf(detail: SessionDetail): TimelineEntry[] {
  const turns = detail.turns.map((turn) => ({
    at: turn.createdAt,
    order: 0,
    entry: {
      kind: turn.channel === 0 ? ('interviewer' as const) : ('you' as const),
      text: turn.content,
    },
  }));
  const answers = detail.answers.map((answer) => ({
    at: answer.createdAt,
    order: 1,
    entry: { kind: 'answer' as const, text: answer.content, trigger: answer.trigger },
  }));

  return [...turns, ...answers]
    .sort((left, right) => left.at.localeCompare(right.at) || left.order - right.order)
    .map((item) => item.entry);
}

/** Human-readable length, or a marker when the session never closed cleanly. */
export function formatDuration(startedAt: string, endedAt: string | null): string {
  if (endedAt === null) return 'unfinished';

  const seconds = Math.round((Date.parse(endedAt) - Date.parse(startedAt)) / 1000);
  // A clock that went backwards between the two writes is not worth rendering
  // as a negative duration.
  if (!Number.isFinite(seconds) || seconds < 0) return 'unknown';
  if (seconds < 60) return `${seconds}s`;

  const minutes = Math.floor(seconds / 60);
  return seconds % 60 === 0 ? `${minutes}m` : `${minutes}m ${seconds % 60}s`;
}

/** What the list shows for a session: how much was in it. */
export function describeSession(summary: SessionSummary): string {
  const count =
    summary.kind === 'practice'
      ? `${summary.practiceQuestions} question${summary.practiceQuestions === 1 ? '' : 's'}`
      : `${summary.answers} answer${summary.answers === 1 ? '' : 's'}`;
  return `${count} · ${formatDuration(summary.startedAt, summary.endedAt)}`;
}

export const speakerLabel = (kind: EntryKind): string =>
  kind === 'interviewer' ? 'Interviewer' : kind === 'you' ? 'You' : 'Answer';

/** Stable, sortable, and safe as a filename on Windows. */
export function exportFileName(summary: SessionSummary): string {
  const date = summary.startedAt.slice(0, 10);
  return `vaderai-${summary.kind}-${date}-${summary.id.slice(0, 8)}.md`;
}
