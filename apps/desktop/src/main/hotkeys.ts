import type { OverlayAction } from '../shared/overlay';

/** Global hotkeys. Mirrored in README.md and docs/002 § Phase 1. */

export const MOVE_STEP_PX = 40;

export const OPACITY_STEP = 0.1;

export const HOTKEYS: ReadonlyArray<{ accelerator: string; action: OverlayAction }> = [
  { accelerator: 'Control+`', action: { type: 'toggle' } },
  { accelerator: 'Control+Enter', action: { type: 'ask' } },
  { accelerator: 'Control+K', action: { type: 'compose' } },
  { accelerator: 'Control+H', action: { type: 'screenshot' } },
  { accelerator: 'Control+Shift+C', action: { type: 'clear' } },
  // The way back from click-through. With the overlay ignoring clicks there is
  // no button left to press, so this cannot be a UI control only.
  { accelerator: 'Control+Shift+X', action: { type: 'click-through' } },
  { accelerator: 'Control+Shift+.', action: { type: 'opacity', delta: OPACITY_STEP } },
  { accelerator: 'Control+Shift+,', action: { type: 'opacity', delta: -OPACITY_STEP } },
  { accelerator: 'Control+Shift+Up', action: { type: 'move', dx: 0, dy: -MOVE_STEP_PX } },
  { accelerator: 'Control+Shift+Down', action: { type: 'move', dx: 0, dy: MOVE_STEP_PX } },
  { accelerator: 'Control+Shift+Left', action: { type: 'move', dx: -MOVE_STEP_PX, dy: 0 } },
  { accelerator: 'Control+Shift+Right', action: { type: 'move', dx: MOVE_STEP_PX, dy: 0 } },
];

/** The slice of Electron's `globalShortcut` we use — injected so this is testable. */
export interface ShortcutRegistrar {
  register(accelerator: string, callback: () => void): boolean;
  unregisterAll(): void;
}

/** Returns the accelerators the OS refused, i.e. another app already owns them. */
export function registerHotkeys(
  registrar: ShortcutRegistrar,
  dispatch: (action: OverlayAction) => void,
): string[] {
  const refused: string[] = [];
  for (const { accelerator, action } of HOTKEYS) {
    if (!registrar.register(accelerator, () => dispatch(action))) refused.push(accelerator);
  }
  return refused;
}
