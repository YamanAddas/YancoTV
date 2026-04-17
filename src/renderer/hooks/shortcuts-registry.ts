// ---------------------------------------------------------------------------
// Keyboard shortcut registry (Sprint 20.7)
//
// All user-rebindable shortcuts live here. `usePlayerShortcuts` reads the
// active binding map from the settings store (`shortcuts_bindings` JSON)
// and dispatches to the matching action.
//
// Shift+Arrow (large seek) is a fixed modifier on top of the seek bindings
// — not configurable, as users rebinding seek would expect the same
// behavior on whatever key they pick. Arrow keys, Escape, and F11 are
// reserved/non-rebindable so users can't lock themselves out.
// ---------------------------------------------------------------------------

export type ShortcutAction =
  | 'playPause'
  | 'muteToggle'
  | 'fullscreen'
  | 'aspectRatio'
  | 'subtitles'
  | 'toggleSettings'
  | 'toggleInfo'
  | 'speedDown'
  | 'speedUp'
  | 'speedReset'
  | 'lastChannel';

export type ShortcutGroup = 'playback' | 'navigation';

export interface ShortcutDef {
  id: ShortcutAction;
  label: string;
  description: string;
  defaultKey: string;
  group: ShortcutGroup;
}

export const SHORTCUT_DEFS: readonly ShortcutDef[] = [
  {
    id: 'playPause',
    label: 'Play / Pause',
    description: 'Toggle playback',
    defaultKey: ' ',
    group: 'playback',
  },
  {
    id: 'muteToggle',
    label: 'Mute / Unmute',
    description: 'Toggle audio mute',
    defaultKey: 'm',
    group: 'playback',
  },
  {
    id: 'fullscreen',
    label: 'Fullscreen',
    description: 'Toggle fullscreen mode',
    defaultKey: 'f',
    group: 'playback',
  },
  {
    id: 'aspectRatio',
    label: 'Aspect ratio',
    description: 'Cycle aspect ratio',
    defaultKey: 'a',
    group: 'playback',
  },
  {
    id: 'subtitles',
    label: 'Subtitles',
    description: 'Toggle subtitles',
    defaultKey: 's',
    group: 'playback',
  },
  {
    id: 'toggleSettings',
    label: 'Player settings',
    description: 'Open/close player settings panel',
    defaultKey: 'g',
    group: 'playback',
  },
  {
    id: 'toggleInfo',
    label: 'Channel info',
    description: 'Open/close channel info panel',
    defaultKey: 'i',
    group: 'playback',
  },
  {
    id: 'speedDown',
    label: 'Speed down',
    description: 'Decrease playback speed',
    defaultKey: '[',
    group: 'playback',
  },
  {
    id: 'speedUp',
    label: 'Speed up',
    description: 'Increase playback speed',
    defaultKey: ']',
    group: 'playback',
  },
  {
    id: 'speedReset',
    label: 'Reset speed',
    description: 'Restore 1x playback speed',
    defaultKey: 'Backspace',
    group: 'playback',
  },
  {
    id: 'lastChannel',
    label: 'Last channel',
    description: 'Jump to previously watched live channel',
    defaultKey: 'l',
    group: 'navigation',
  },
];

export const SHORTCUTS_SETTING_KEY = 'shortcuts_bindings';

export const DEFAULT_BINDINGS: Record<ShortcutAction, string> = SHORTCUT_DEFS.reduce(
  (acc, def) => {
    acc[def.id] = def.defaultKey;
    return acc;
  },
  {} as Record<ShortcutAction, string>,
);

/**
 * Parse the persisted JSON map, falling back to defaults for any missing or
 * invalid entries. A malformed value is treated as "no overrides."
 */
export function parseBindings(raw: string): Record<ShortcutAction, string> {
  const out: Record<ShortcutAction, string> = { ...DEFAULT_BINDINGS };
  if (!raw) return out;
  try {
    const parsed = JSON.parse(raw) as Partial<Record<ShortcutAction, unknown>>;
    for (const def of SHORTCUT_DEFS) {
      const v = parsed[def.id];
      if (typeof v === 'string' && v.length > 0) {
        out[def.id] = v;
      }
    }
  } catch {
    // Malformed JSON → defaults.
  }
  return out;
}

/**
 * Build the inverse map (key → action) used by the keydown handler. Keys are
 * normalized so "A" and "a" both match an "a" binding.
 */
export function buildKeyMap(
  bindings: Record<ShortcutAction, string>,
): Map<string, ShortcutAction> {
  const map = new Map<string, ShortcutAction>();
  for (const def of SHORTCUT_DEFS) {
    const key = bindings[def.id] ?? def.defaultKey;
    map.set(normalizeKey(key), def.id);
  }
  return map;
}

/** Normalize a KeyboardEvent.key or a stored binding to a comparable form. */
export function normalizeKey(key: string): string {
  if (key.length === 1) return key.toLowerCase();
  return key;
}

/** Human-readable label for a key string, used in the settings UI. */
export function formatKeyLabel(key: string): string {
  if (key === ' ') return 'Space';
  if (key === 'Backspace') return 'Backspace';
  if (key === 'Escape') return 'Escape';
  if (key === 'Enter') return 'Enter';
  if (key === 'Tab') return 'Tab';
  if (key.length === 1) return key.toUpperCase();
  return key;
}

/** Keys the user is NOT allowed to rebind to (reserved / unsafe). */
const RESERVED_KEYS = new Set([
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'Escape',
  'F11',
  'Tab',
  'Enter',
  'Control',
  'Shift',
  'Alt',
  'Meta',
]);

export function isReservedKey(key: string): boolean {
  return RESERVED_KEYS.has(key);
}
