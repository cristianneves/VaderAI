import { Channel } from '@vaderai/protocol';
import { describe, expect, it } from 'vitest';
import { applyTranscript, MAX_LINES, speakerOf, type TranscriptLine } from './log';

const interviewer = (text: string, isFinal = false) => ({
  channel: Channel.Interviewer,
  text,
  isFinal,
});
const user = (text: string, isFinal = false) => ({ channel: Channel.User, text, isFinal });

describe('applyTranscript', () => {
  it('appends the first interim of a channel', () => {
    expect(applyTranscript([], interviewer('tell me'))).toEqual([interviewer('tell me')]);
  });

  it('rewrites the pending line instead of appending', () => {
    const lines = applyTranscript([], interviewer('tell'));

    expect(applyTranscript(lines, interviewer('tell me about'))).toEqual([
      interviewer('tell me about'),
    ]);
  });

  it('commits the pending line when the final arrives', () => {
    const lines = applyTranscript([], interviewer('tell me'));

    const committed = applyTranscript(lines, interviewer('Tell me about yourself.', true));

    expect(committed).toEqual([interviewer('Tell me about yourself.', true)]);
  });

  it('starts a new line after the previous one was finalized', () => {
    let lines = applyTranscript([], interviewer('first', true));
    lines = applyTranscript(lines, interviewer('second'));

    expect(lines).toEqual([interviewer('first', true), interviewer('second')]);
  });

  it('keeps the two speakers on separate lines', () => {
    let lines = applyTranscript([], interviewer('tell me'));
    lines = applyTranscript(lines, user('sure, at Acme'));
    lines = applyTranscript(lines, interviewer('tell me about scaling'));

    expect(lines).toEqual([interviewer('tell me about scaling'), user('sure, at Acme')]);
  });

  it('never lets one speaker overwrite the other', () => {
    const lines = applyTranscript(
      applyTranscript([], interviewer('question')),
      user('answer', true),
    );

    expect(lines.map((l) => l.channel)).toEqual([Channel.Interviewer, Channel.User]);
  });

  it('drops the oldest lines past the cap', () => {
    let lines: TranscriptLine[] = [];
    for (let i = 0; i < MAX_LINES + 10; i += 1) {
      lines = applyTranscript(lines, interviewer(`line ${i}`, true));
    }

    expect(lines).toHaveLength(MAX_LINES);
    expect(lines[0]?.text).toBe('line 10');
  });

  it('does not mutate the array it was given', () => {
    const original = applyTranscript([], interviewer('one', true));
    applyTranscript(original, interviewer('two'));

    expect(original).toHaveLength(1);
  });
});

describe('speakerOf', () => {
  it('labels channel 0 as the interviewer and channel 1 as you', () => {
    expect(speakerOf(Channel.Interviewer)).toBe('Interviewer');
    expect(speakerOf(Channel.User)).toBe('You');
  });
});
