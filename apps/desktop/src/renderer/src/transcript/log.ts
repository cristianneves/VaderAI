import { Channel } from '@vaderai/protocol';

export interface TranscriptLine {
  channel: Channel;
  text: string;
  isFinal: boolean;
}

/** An hour of interview is thousands of turns; the overlay only shows the tail. */
export const MAX_LINES = 200;

export const speakerOf = (channel: Channel): string =>
  channel === Channel.Interviewer ? 'Interviewer' : 'You';

/**
 * Interim results arrive several times a second and rewrite the tail of the
 * line they belong to; a final commits it. Each channel has at most one line in
 * flight, so the two speakers never overwrite each other.
 */
export function applyTranscript(
  lines: readonly TranscriptLine[],
  update: { channel: Channel; text: string; isFinal: boolean },
): TranscriptLine[] {
  const next = [...lines];
  const pending = next.findLastIndex((line) => line.channel === update.channel && !line.isFinal);

  if (pending === -1) {
    next.push({ ...update });
  } else {
    next[pending] = { ...update };
  }

  return next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next;
}
