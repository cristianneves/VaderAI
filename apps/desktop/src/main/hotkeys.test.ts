import { describe, expect, it, vi } from 'vitest';
import { HOTKEYS, MOVE_STEP_PX, registerHotkeys, type ShortcutRegistrar } from './hotkeys';
import type { OverlayAction } from '../shared/overlay';

function fakeRegistrar(refuse: string[] = []): {
  registrar: ShortcutRegistrar;
  fire: (accelerator: string) => void;
} {
  const callbacks = new Map<string, () => void>();
  return {
    registrar: {
      register: (accelerator, callback) => {
        if (refuse.includes(accelerator)) return false;
        callbacks.set(accelerator, callback);
        return true;
      },
      unregisterAll: vi.fn(),
    },
    fire: (accelerator) => callbacks.get(accelerator)?.(),
  };
}

describe('registerHotkeys', () => {
  it('registers every hotkey and reports none refused', () => {
    const { registrar } = fakeRegistrar();
    expect(registerHotkeys(registrar, vi.fn())).toEqual([]);
  });

  it('dispatches the mapped action when a hotkey fires', () => {
    const dispatch = vi.fn<(action: OverlayAction) => void>();
    const { registrar, fire } = fakeRegistrar();
    registerHotkeys(registrar, dispatch);

    fire('Control+`');
    fire('Control+Shift+Left');

    expect(dispatch).toHaveBeenNthCalledWith(1, { type: 'toggle' });
    expect(dispatch).toHaveBeenNthCalledWith(2, { type: 'move', dx: -MOVE_STEP_PX, dy: 0 });
  });

  it('returns the accelerators the OS refused', () => {
    const { registrar } = fakeRegistrar(['Control+H']);
    expect(registerHotkeys(registrar, vi.fn())).toEqual(['Control+H']);
  });

  it('does not dispatch for a refused accelerator', () => {
    const dispatch = vi.fn();
    const { registrar, fire } = fakeRegistrar(['Control+H']);
    registerHotkeys(registrar, dispatch);

    fire('Control+H');

    expect(dispatch).not.toHaveBeenCalled();
  });
});

describe('HOTKEYS', () => {
  it('has no duplicate accelerators', () => {
    const accelerators = HOTKEYS.map((h) => h.accelerator);
    expect(new Set(accelerators).size).toBe(accelerators.length);
  });

  it('covers all five documented actions', () => {
    const types = new Set(HOTKEYS.map((h) => h.action.type));
    expect(types).toEqual(new Set(['toggle', 'ask', 'screenshot', 'clear', 'move']));
  });
});
