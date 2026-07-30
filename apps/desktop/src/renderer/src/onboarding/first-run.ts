import type { KnowledgeView } from '../settings/knowledge';

/** The three things a new install needs before it is useful, in order. */
export type FirstRunStep = 'signin' | 'microphone' | 'background';

/** `'unknown'` covers a runtime where the Permissions API cannot answer. */
export type MicPermission = PermissionState | 'unknown';

export interface FirstRunState {
  signedIn: boolean;
  micPermission: MicPermission;
  /** Null while it is still being fetched — not the same as "empty". */
  knowledge: KnowledgeView | null;
  dismissed: boolean;
}

const DISMISSED_KEY = 'vaderai.first-run.dismissed';

/** A stored document that is only whitespace grounds nothing. */
export function hasBackground(view: KnowledgeView | null): boolean {
  return view !== null && view.documents.some((document) => document.content.trim() !== '');
}

/**
 * The next thing to ask the user for, or null when there is nothing to ask.
 *
 * Sign-in is never suppressed — dismissing it would leave the app in a state
 * where nothing works and nothing explains why. The other two are: the mic can
 * be granted later from the OS, and the knowledge base is a quality lever
 * rather than a requirement.
 */
export function nextStep(state: FirstRunState): FirstRunStep | null {
  if (!state.signedIn) return 'signin';
  if (state.dismissed) return null;
  if (state.micPermission !== 'granted') return 'microphone';
  // Still loading: showing the prompt now would flash it at someone who already
  // has a résumé stored.
  if (state.knowledge === null) return null;
  return hasBackground(state.knowledge) ? null : 'background';
}

export const isDismissed = (storage: Pick<Storage, 'getItem' | 'setItem'>): boolean =>
  storage.getItem(DISMISSED_KEY) === 'true';

export const dismiss = (storage: Pick<Storage, 'getItem' | 'setItem'>): void =>
  storage.setItem(DISMISSED_KEY, 'true');

/**
 * Asks the OS for the mic. This is also how the permission gets granted in the
 * first place — the Permissions API only reports, it never prompts. The track
 * is stopped straight away; the real capture opens its own with different
 * constraints.
 */
export async function requestMicrophone(): Promise<MicPermission> {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    for (const track of stream.getTracks()) track.stop();
    return 'granted';
  } catch {
    return 'denied';
  }
}

/** Reads the current state without prompting. */
export async function readMicPermission(): Promise<MicPermission> {
  try {
    const status = await navigator.permissions.query({
      name: 'microphone' as PermissionName,
    });
    return status.state;
  } catch {
    return 'unknown';
  }
}
