/**
 * One-tap questions for the ask bar. They are ordinary typed questions — the
 * server has no idea these came from a button — so adding one costs a line and
 * no protocol change.
 *
 * Phrased as instructions to the copilot rather than as interview questions,
 * because that is what the system prompt tells it to expect from a typed
 * request.
 */
export interface QuickAction {
  readonly label: string;
  readonly question: string;
}

export const QUICK_ACTIONS: readonly QuickAction[] = [
  { label: 'What next?', question: 'What should I say next?' },
  { label: 'Shorter', question: 'Give me a shorter version of that answer.' },
  { label: 'More detail', question: 'Go deeper on that — add the specifics I left out.' },
  { label: 'Recap', question: 'Recap what has been discussed so far, briefly.' },
];
