/**
 * The one decision in the Markdown renderer worth testing on its own.
 *
 * <p>react-markdown hands both fenced blocks and inline spans to the same `code`
 * component. Getting this wrong is visible: a `const x = 1` mentioned mid
 * sentence would grow a copy button and a header, or a twenty-line function
 * would render inline and blow out the pane.
 */

/** A fence carries `language-x`; an inline span carries no class. */
export function isCodeBlock(className: string | undefined, source: string): boolean {
  if (className !== undefined) return true;
  // A fence with no language tag still arrives classless, so fall back to the
  // only other signal there is.
  return source.includes('\n');
}

/** `language-python` -> `python`. Undefined for a fence with no tag. */
export function languageOf(className: string | undefined): string | undefined {
  if (className === undefined) return undefined;
  const match = /language-([\w+-]+)/.exec(className);
  return match?.[1];
}
