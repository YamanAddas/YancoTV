// ---------------------------------------------------------------------------
// Keyboard Shortcuts — display current shortcuts, future: customization
// ---------------------------------------------------------------------------

interface ShortcutDef {
  key: string;
  description: string;
}

const playbackShortcuts: ShortcutDef[] = [
  { key: 'Space', description: 'Play / Pause toggle' },
  { key: 'Escape', description: 'Stop playback' },
  { key: '\u2190 Left', description: 'Seek backward 10 seconds' },
  { key: '\u2192 Right', description: 'Seek forward 10 seconds' },
  { key: '\u2191 Up', description: 'Volume up 5%' },
  { key: '\u2193 Down', description: 'Volume down 5%' },
  { key: 'M', description: 'Mute / Unmute' },
  { key: 'F', description: 'Toggle fullscreen' },
  { key: 'A', description: 'Cycle aspect ratio' },
  { key: 'S', description: 'Cycle playback speed' },
];

const navigationShortcuts: ShortcutDef[] = [
  { key: 'Ctrl + F', description: 'Focus search bar' },
  { key: 'Ctrl + ,', description: 'Open Settings' },
  { key: 'F11', description: 'Toggle fullscreen' },
  { key: 'Ctrl + R', description: 'Refresh current page' },
];

function ShortcutTable({
  title,
  shortcuts,
}: {
  title: string;
  shortcuts: ShortcutDef[];
}) {
  return (
    <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
      <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
        {title}
      </h3>
      <div className="space-y-1">
        {shortcuts.map((s) => (
          <div
            key={s.key}
            className="flex items-center justify-between rounded-lg px-3 py-2 transition-colors hover:bg-surface-800/60"
          >
            <span className="text-sm text-surface-300">{s.description}</span>
            <kbd className="rounded-md border border-surface-700 bg-surface-800 px-2.5 py-1 text-xs font-mono text-surface-300">
              {s.key}
            </kbd>
          </div>
        ))}
      </div>
    </section>
  );
}

export function ShortcutsSettings() {
  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">
          Keyboard Shortcuts
        </h2>
        <p className="mt-1 text-sm text-surface-500">
          Quick reference for keyboard controls
        </p>
      </div>

      <ShortcutTable title="Playback" shortcuts={playbackShortcuts} />
      <ShortcutTable title="Navigation" shortcuts={navigationShortcuts} />

      <div className="rounded-lg border border-surface-700/50 bg-surface-900/30 px-4 py-3">
        <p className="text-xs text-surface-500">
          <span className="font-medium text-surface-400">Note:</span>{' '}
          Playback shortcuts are only active when a channel or video is
          playing. They are disabled when typing in input fields.
        </p>
      </div>
    </div>
  );
}
