import { useState, useEffect, useRef } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import {
  SHORTCUT_DEFS,
  SHORTCUTS_SETTING_KEY,
  DEFAULT_BINDINGS,
  formatKeyLabel,
  isReservedKey,
  parseBindings,
  type ShortcutAction,
  type ShortcutGroup,
} from '../../hooks/shortcuts-registry';

// ---------------------------------------------------------------------------
// Customizable keyboard shortcuts (Sprint 20.7)
// ---------------------------------------------------------------------------

const FIXED_SHORTCUTS: { key: string; description: string }[] = [
  { key: '\u2190 / \u2192', description: 'Seek backward / forward 10s' },
  { key: 'Shift + \u2190 / \u2192', description: 'Seek backward / forward 30s' },
  { key: '\u2191 / \u2193', description: 'Volume up / down 5%' },
  { key: 'Escape', description: 'Exit fullscreen / stop playback' },
  { key: 'F11', description: 'Toggle fullscreen' },
  { key: 'PageUp / PageDown', description: 'Channel zap (live TV)' },
];

const NAV_SHORTCUTS: { key: string; description: string }[] = [
  { key: 'Ctrl + F', description: 'Focus search bar' },
  { key: 'Ctrl + ,', description: 'Open Settings' },
  { key: 'Ctrl + R', description: 'Refresh current page' },
];

function KeyBadge({ label }: { label: string }) {
  return (
    <kbd className="inline-flex min-w-[2.5rem] items-center justify-center rounded-md border border-surface-700/50 bg-surface-800/40 px-2.5 py-1 text-xs font-mono text-surface-300">
      {label}
    </kbd>
  );
}

function RebindButton({
  actionId,
  currentKey,
  onRebind,
  bindings,
}: {
  actionId: ShortcutAction;
  currentKey: string;
  onRebind: (key: string) => void;
  bindings: Record<ShortcutAction, string>;
}) {
  const [listening, setListening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const btnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!listening) return;

    function onKey(e: KeyboardEvent) {
      e.preventDefault();
      e.stopPropagation();

      if (e.key === 'Escape') {
        setListening(false);
        setError(null);
        return;
      }

      if (isReservedKey(e.key)) {
        setError(`${formatKeyLabel(e.key)} is reserved`);
        return;
      }
      if (e.ctrlKey || e.metaKey || e.altKey) {
        setError('Modifier combos not supported');
        return;
      }

      const normalized = e.key;

      const conflict = (Object.entries(bindings) as [ShortcutAction, string][])
        .find(([id, k]) => id !== actionId && k.toLowerCase() === normalized.toLowerCase());
      if (conflict) {
        const def = SHORTCUT_DEFS.find((d) => d.id === conflict[0]);
        setError(`Already used by "${def?.label ?? conflict[0]}"`);
        return;
      }

      onRebind(normalized);
      setListening(false);
      setError(null);
    }

    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [listening, actionId, bindings, onRebind]);

  useEffect(() => {
    if (listening && btnRef.current) {
      btnRef.current.focus();
    }
  }, [listening]);

  if (listening) {
    return (
      <button
        ref={btnRef}
        type="button"
        onClick={() => setListening(false)}
        className="flex min-w-[7rem] items-center justify-center rounded-md border border-accent/60 bg-accent/10 px-3 py-1 text-xs font-mono text-accent outline-none ring-2 ring-accent/40"
      >
        {error ?? 'Press a key…'}
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={() => {
        setError(null);
        setListening(true);
      }}
      className="flex min-w-[7rem] items-center justify-center rounded-md border border-surface-700/50 bg-surface-800/40 px-3 py-1 text-xs font-mono text-surface-300 transition-colors hover:border-accent/40 hover:bg-surface-800/70 hover:text-surface-100"
      title="Click to rebind"
    >
      {formatKeyLabel(currentKey)}
    </button>
  );
}

function RebindableSection({
  title,
  group,
  bindings,
  onUpdate,
}: {
  title: string;
  group: ShortcutGroup;
  bindings: Record<ShortcutAction, string>;
  onUpdate: (id: ShortcutAction, key: string) => void;
}) {
  const rows = SHORTCUT_DEFS.filter((d) => d.group === group);
  if (rows.length === 0) return null;

  return (
    <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
      <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
        {title}
      </h3>
      <div className="space-y-1">
        {rows.map((def) => (
          <div
            key={def.id}
            className="flex items-center justify-between rounded-lg px-3 py-2 transition-colors hover:bg-surface-800/60"
          >
            <div>
              <div className="text-sm text-surface-200">{def.label}</div>
              <div className="text-xs text-surface-500">{def.description}</div>
            </div>
            <RebindButton
              actionId={def.id}
              currentKey={bindings[def.id]}
              bindings={bindings}
              onRebind={(k) => onUpdate(def.id, k)}
            />
          </div>
        ))}
      </div>
    </section>
  );
}

function FixedSection({
  title,
  items,
}: {
  title: string;
  items: { key: string; description: string }[];
}) {
  return (
    <section className="rounded-xl border border-accent/5 bg-surface-900/30 p-5">
      <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
        {title}
      </h3>
      <div className="space-y-1">
        {items.map((s) => (
          <div
            key={s.key}
            className="flex items-center justify-between rounded-lg px-3 py-2"
          >
            <span className="text-sm text-surface-300">{s.description}</span>
            <KeyBadge label={s.key} />
          </div>
        ))}
      </div>
    </section>
  );
}

export function ShortcutsSettings() {
  const rawBindings = useSettingsStore((s) => s.data[SHORTCUTS_SETTING_KEY] ?? '');
  const setSetting = useSettingsStore((s) => s.set);

  const bindings = parseBindings(rawBindings);

  const updateBinding = (id: ShortcutAction, key: string) => {
    const next = { ...bindings, [id]: key };
    void setSetting(SHORTCUTS_SETTING_KEY, JSON.stringify(next));
  };

  const resetAll = () => {
    void setSetting(SHORTCUTS_SETTING_KEY, JSON.stringify(DEFAULT_BINDINGS));
  };

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-surface-100">
            Keyboard Shortcuts
          </h2>
          <p className="mt-1 text-sm text-surface-500">
            Click any key to rebind. Press Escape during capture to cancel.
          </p>
        </div>
        <button
          type="button"
          onClick={resetAll}
          className="shrink-0 rounded-md border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-xs text-surface-300 transition-colors hover:border-accent/40 hover:text-surface-100"
        >
          Reset all
        </button>
      </div>

      <RebindableSection
        title="Playback"
        group="playback"
        bindings={bindings}
        onUpdate={updateBinding}
      />
      <RebindableSection
        title="Navigation"
        group="navigation"
        bindings={bindings}
        onUpdate={updateBinding}
      />
      <FixedSection title="Fixed (non-rebindable)" items={FIXED_SHORTCUTS} />
      <FixedSection title="App navigation" items={NAV_SHORTCUTS} />

      <div className="rounded-lg border border-surface-700/50 bg-surface-900/30 px-4 py-3">
        <p className="text-xs text-surface-500">
          <span className="font-medium text-surface-400">Note:</span>{' '}
          Playback shortcuts are active only while a channel or video is
          playing. They are disabled when typing in input fields.
        </p>
      </div>
    </div>
  );
}
