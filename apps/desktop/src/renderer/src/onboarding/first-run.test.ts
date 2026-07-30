import { describe, expect, it } from 'vitest';
import { EMPTY_VIEW, type KnowledgeView } from '../settings/knowledge';
import { dismiss, hasBackground, isDismissed, nextStep, type FirstRunState } from './first-run';

const withResume: KnowledgeView = {
  ...EMPTY_VIEW,
  documents: [{ kind: 'resume', title: 'CV', content: 'Cut deploy time from 40 to 6 minutes.' }],
  tokens: 530,
};

const READY: FirstRunState = {
  signedIn: true,
  micPermission: 'granted',
  knowledge: withResume,
  dismissed: false,
};

describe('hasBackground', () => {
  it('is false while the knowledge base is still loading', () => {
    expect(hasBackground(null)).toBe(false);
  });

  it('is false for no documents', () => {
    expect(hasBackground(EMPTY_VIEW)).toBe(false);
  });

  it('is false for a document that is only whitespace', () => {
    expect(
      hasBackground({
        ...EMPTY_VIEW,
        documents: [{ kind: 'notes', title: null, content: '   \n\t ' }],
      }),
    ).toBe(false);
  });

  it('is true once any document has real content', () => {
    expect(hasBackground(withResume)).toBe(true);
  });
});

describe('nextStep', () => {
  it('asks for sign-in first', () => {
    expect(nextStep({ ...READY, signedIn: false })).toBe('signin');
  });

  it('asks for sign-in even when the user dismissed onboarding', () => {
    // Dismissing must not strand someone in an app where nothing works.
    expect(nextStep({ ...READY, signedIn: false, dismissed: true })).toBe('signin');
  });

  it('asks for the microphone after sign-in', () => {
    expect(nextStep({ ...READY, micPermission: 'prompt' })).toBe('microphone');
  });

  it('still asks when permission was denied — it is fixable in the OS', () => {
    expect(nextStep({ ...READY, micPermission: 'denied' })).toBe('microphone');
  });

  it('asks when the Permissions API cannot answer', () => {
    expect(nextStep({ ...READY, micPermission: 'unknown' })).toBe('microphone');
  });

  it('asks for the knowledge base once the mic is granted and nothing is stored', () => {
    expect(nextStep({ ...READY, knowledge: EMPTY_VIEW })).toBe('background');
  });

  it('stays quiet while the knowledge base is still loading', () => {
    expect(nextStep({ ...READY, knowledge: null })).toBeNull();
  });

  it('is finished when all three are satisfied', () => {
    expect(nextStep(READY)).toBeNull();
  });

  it('is silent after dismissal, even with nothing stored', () => {
    expect(
      nextStep({ ...READY, micPermission: 'prompt', knowledge: EMPTY_VIEW, dismissed: true }),
    ).toBeNull();
  });
});

describe('dismissal', () => {
  function fakeStorage(): Pick<Storage, 'getItem' | 'setItem'> {
    const values = new Map<string, string>();
    return {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => void values.set(key, value),
    };
  }

  it('is not dismissed on a fresh install', () => {
    expect(isDismissed(fakeStorage())).toBe(false);
  });

  it('survives being written and read back', () => {
    const storage = fakeStorage();
    dismiss(storage);
    expect(isDismissed(storage)).toBe(true);
  });
});
