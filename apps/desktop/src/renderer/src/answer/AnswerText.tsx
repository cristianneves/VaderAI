import { useState } from 'react';
import Markdown from 'react-markdown';
import { isCodeBlock, languageOf } from './markdown';

/**
 * Renders an answer as Markdown.
 *
 * <p>Until Phase 9 this was a single `<p>` with `white-space: pre-wrap`, which
 * meant the `Ctrl+H` path — the one aimed at LeetCode problems — returned code
 * as an unformatted wall of text. Code blocks get a copy button because the
 * overlay is `focusable: false`, so Ctrl+C on a selection is not reliable.
 */
export function AnswerText({ text }: { readonly text: string }): React.JSX.Element {
  return (
    <div className="answer-text">
      <Markdown
        components={{
          // Dropped so the code override owns the whole block; otherwise its
          // wrapper would end up nested inside react-markdown's own <pre>.
          pre: ({ children }) => <>{children}</>,
          code: ({ className, children, ...rest }) => {
            const source = String(children).replace(/\n$/, '');
            if (!isCodeBlock(className, source)) {
              return (
                <code className="inline-code" {...rest}>
                  {children}
                </code>
              );
            }
            return <CodeBlock className={className} source={source} />;
          },
        }}
      >
        {text}
      </Markdown>
    </div>
  );
}

function CodeBlock({
  className,
  source,
}: {
  readonly className?: string;
  readonly source: string;
}): React.JSX.Element {
  const [copied, setCopied] = useState(false);
  const language = languageOf(className);

  return (
    <div className="code-block">
      <div className="code-head">
        <span className="meta">{language ?? 'code'}</span>
        <button
          className="chip"
          onClick={() => {
            window.vader.copyText(source);
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
          }}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre>
        <code className={className}>{source}</code>
      </pre>
    </div>
  );
}
