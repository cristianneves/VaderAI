export type KnowledgeKind = 'resume' | 'job_description' | 'notes';

export interface KnowledgeDocument {
  kind: KnowledgeKind;
  title: string | null;
  content: string;
}

export interface KnowledgeView {
  documents: KnowledgeDocument[];
  tokens: number;
  estimated: boolean;
  ceiling: number;
}

/** Order matches the server's assembly order, so the screen reads the way the prompt does. */
export const KNOWLEDGE_KINDS: { kind: KnowledgeKind; label: string; hint: string }[] = [
  {
    kind: 'resume',
    label: 'Résumé',
    hint: 'What you have actually done — the source of specifics.',
  },
  { kind: 'job_description', label: 'Job description', hint: 'What this role is looking for.' },
  { kind: 'notes', label: 'Notes', hint: 'Anything else worth grounding answers in.' },
];

export const EMPTY_VIEW: KnowledgeView = {
  documents: [],
  tokens: 0,
  estimated: true,
  ceiling: 8000,
};

export const contentOf = (view: KnowledgeView, kind: KnowledgeKind): string =>
  view.documents.find((document) => document.kind === kind)?.content ?? '';

export const isOverBudget = (view: KnowledgeView): boolean => view.tokens > view.ceiling;

/**
 * An estimate is marked as one. The server counts with the model's own
 * tokenizer when a key is configured and falls back to a character ratio when
 * it cannot — showing "~" keeps that distinction visible.
 */
export const formatTokens = (view: KnowledgeView): string =>
  `${view.estimated ? '~' : ''}${view.tokens.toLocaleString()} tokens`;
