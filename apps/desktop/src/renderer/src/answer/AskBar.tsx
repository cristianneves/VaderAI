import { MAX_QUESTION_CHARS } from '@vaderai/protocol';
import { useEffect, useRef, useState } from 'react';
import { QUICK_ACTIONS } from './quick-actions';

interface AskBarProps {
  /** True while the composer should hold keyboard focus. */
  readonly open: boolean;
  readonly disabled: boolean;
  readonly onAsk: (question: string) => void;
  readonly onClose: () => void;
}

/**
 * The ask bar: type a question, or send one of the quick actions.
 *
 * Focus is the awkward part. The overlay is `focusable: false` so it never
 * steals focus from the meeting, which means the textarea receives nothing
 * until main has granted focus — `onClose` is what hands it back, and every
 * path out of here goes through it.
 */
export function AskBar({ open, disabled, onAsk, onClose }: AskBarProps): React.JSX.Element {
  const [text, setText] = useState('');
  const input = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (open) input.current?.focus();
  }, [open]);

  function send(question: string): void {
    const trimmed = question.trim();
    if (trimmed === '' || disabled) return;
    onAsk(trimmed);
    setText('');
    onClose();
  }

  return (
    <div className="ask-bar">
      <div className="quick-actions">
        {QUICK_ACTIONS.map((action) => (
          <button
            key={action.label}
            className="chip"
            disabled={disabled}
            onClick={() => send(action.question)}
          >
            {action.label}
          </button>
        ))}
      </div>
      <textarea
        ref={input}
        className="ask-input"
        rows={2}
        value={text}
        maxLength={MAX_QUESTION_CHARS}
        disabled={disabled}
        placeholder={disabled ? 'Start listening to ask' : 'Ask anything — Enter to send'}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          // Shift+Enter is a newline; Enter alone sends, which is what someone
          // mid-interview will try first.
          if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            send(text);
          } else if (event.key === 'Escape') {
            event.preventDefault();
            onClose();
          }
        }}
      />
    </div>
  );
}
