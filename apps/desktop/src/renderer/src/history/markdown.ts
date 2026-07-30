import type { PracticeQuestion } from '../practice/practice';
import {
  formatDuration,
  speakerLabel,
  timelineOf,
  type SessionDetail,
  type SessionSummary,
} from './history';

const TRIGGER_NOTE: Record<string, string> = {
  auto: '',
  manual: ' _(asked manually)_',
  // Worth calling out: there is no spoken question above it to pair with.
  screenshot: ' _(about the screen)_',
};

/**
 * A session as a Markdown document.
 *
 * <p>Pure on purpose — the main process only writes the bytes, so the formatting
 * is testable without Electron.
 */
export function toMarkdown(
  summary: SessionSummary,
  detail: SessionDetail,
  practiceQuestions: PracticeQuestion[] = [],
): string {
  const lines: string[] = [];
  const kind = summary.kind === 'practice' ? 'Practice session' : 'Session';

  lines.push(`# ${kind} — ${summary.startedAt.slice(0, 10)}`);
  lines.push('');
  lines.push(`Started: ${summary.startedAt}`);
  lines.push(`Duration: ${formatDuration(summary.startedAt, summary.endedAt)}`);
  lines.push('');

  if (summary.kind === 'practice') {
    lines.push(...practiceSection(practiceQuestions));
  } else {
    lines.push(...transcriptSection(detail));
  }

  return `${lines.join('\n').trimEnd()}\n`;
}

function transcriptSection(detail: SessionDetail): string[] {
  const timeline = timelineOf(detail);
  if (timeline.length === 0) {
    // Still a valid document: an empty session is a fact worth exporting, not
    // a reason to write a zero-byte file.
    return ['## Transcript', '', '_Nothing was captured in this session._'];
  }

  const lines = ['## Transcript', ''];
  for (const entry of timeline) {
    if (entry.kind === 'answer') {
      lines.push(`**${speakerLabel(entry.kind)}**${TRIGGER_NOTE[entry.trigger ?? 'auto'] ?? ''}`);
      lines.push('');
      lines.push(`> ${entry.text.split('\n').join('\n> ')}`);
    } else {
      lines.push(`**${speakerLabel(entry.kind)}:** ${entry.text}`);
    }
    lines.push('');
  }
  return lines;
}

function practiceSection(questions: PracticeQuestion[]): string[] {
  if (questions.length === 0) {
    return ['## Questions', '', '_No questions were generated._'];
  }

  const lines: string[] = [];
  for (const question of questions) {
    lines.push(`## ${question.position + 1}. ${question.question}`);
    lines.push('');
    if (question.answer === null) {
      lines.push('_Not answered._');
      lines.push('');
      continue;
    }
    lines.push(`**Your answer:** ${question.answer}`);
    lines.push('');
    lines.push(
      `Structure ${question.structure}/5 · Specificity ${question.specificity}/5 · Relevance ${question.relevance}/5`,
    );
    lines.push('');
    if (question.feedback !== null) {
      lines.push(`**Feedback:** ${question.feedback}`);
      lines.push('');
    }
    if (question.rewrite !== null) {
      lines.push('**Say it like this**');
      lines.push('');
      lines.push(`> ${question.rewrite.split('\n').join('\n> ')}`);
      lines.push('');
    }
  }
  return lines;
}
