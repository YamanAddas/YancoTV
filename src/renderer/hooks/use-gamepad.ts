import { useEffect } from 'react';

/**
 * Basic gamepad input mapping (Sprint 20.8).
 *
 * Polls the Gamepad API at ~60Hz and translates D-pad + face buttons into
 * synthetic `keydown` events on `window`. This lets the existing keyboard-
 * driven navigation (focus rings, arrow traversal, player shortcuts) work
 * with a gamepad with zero extra wiring downstream.
 *
 * Mapping (standard gamepad layout — Xbox / PlayStation / most PC pads):
 *   D-pad up/down/left/right  → ArrowUp / ArrowDown / ArrowLeft / ArrowRight
 *   A (button 0)              → Enter
 *   B (button 1)              → Escape
 *   X (button 2)              → f  (toggle fullscreen via player shortcut)
 *   Y (button 3)              → i  (info / settings toggle)
 *   LB / RB (4 / 5)           → PageUp / PageDown (channel zap)
 *   Start (9)                 → g  (settings panel toggle)
 *
 * Repeat handling: a button must be released before it fires again. D-pad
 * direction holds auto-repeat after a short initial delay so arrowing a
 * long list feels natural.
 */

const BUTTON_KEY_MAP: Record<number, string> = {
  0: 'Enter',
  1: 'Escape',
  2: 'f',
  3: 'i',
  4: 'PageUp',
  5: 'PageDown',
  9: 'g',
  12: 'ArrowUp',
  13: 'ArrowDown',
  14: 'ArrowLeft',
  15: 'ArrowRight',
};

const REPEATABLE_BUTTONS = new Set([12, 13, 14, 15]);
const INITIAL_REPEAT_DELAY_MS = 400;
const REPEAT_INTERVAL_MS = 100;

export function useGamepad(): void {
  useEffect(() => {
    // Some embedded environments (tests / headless) don't have getGamepads.
    if (typeof navigator === 'undefined' || typeof navigator.getGamepads !== 'function') {
      return;
    }

    let running = true;
    let rafId = 0;
    // Track per-button state so we fire once on press, then auto-repeat for
    // directional buttons only.
    const pressedAt: Record<number, number> = {};
    const lastRepeatAt: Record<number, number> = {};

    function dispatchKey(key: string): void {
      window.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
    }

    function scan(): void {
      if (!running) return;
      const pads = navigator.getGamepads();
      const now = performance.now();
      for (const pad of pads) {
        if (!pad) continue;
        for (let i = 0; i < pad.buttons.length; i++) {
          const pressed = pad.buttons[i]?.pressed ?? false;
          const key = BUTTON_KEY_MAP[i];
          if (!key) continue;
          if (pressed && pressedAt[i] === undefined) {
            // Rising edge — fire once.
            pressedAt[i] = now;
            lastRepeatAt[i] = now;
            dispatchKey(key);
          } else if (pressed && REPEATABLE_BUTTONS.has(i)) {
            // Held — fire again after initial delay, then at interval.
            const heldFor = now - (pressedAt[i] ?? now);
            const sinceLast = now - (lastRepeatAt[i] ?? now);
            if (heldFor >= INITIAL_REPEAT_DELAY_MS && sinceLast >= REPEAT_INTERVAL_MS) {
              lastRepeatAt[i] = now;
              dispatchKey(key);
            }
          } else if (!pressed && pressedAt[i] !== undefined) {
            delete pressedAt[i];
            delete lastRepeatAt[i];
          }
        }
      }
      rafId = requestAnimationFrame(scan);
    }

    rafId = requestAnimationFrame(scan);

    return () => {
      running = false;
      cancelAnimationFrame(rafId);
    };
  }, []);
}
