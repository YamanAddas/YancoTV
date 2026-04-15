import { useEffect } from 'react';
import { useSettingsStore } from '../../stores/settings-store';

// ---------------------------------------------------------------------------
// General Settings — theme, startup, language, UI preferences
// ---------------------------------------------------------------------------

function SettingRow({
  label,
  description,
  children,
}: {
  label: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3 transition-colors hover:border-accent/10">
      <div className="min-w-0">
        <p className="text-sm font-medium text-surface-200">{label}</p>
        {description && (
          <p className="mt-0.5 text-xs text-surface-500">{description}</p>
        )}
      </div>
      <div className="flex-shrink-0">{children}</div>
    </div>
  );
}

function Toggle({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-all ${
        checked ? 'bg-accent shadow-glow-sm' : 'bg-surface-600'
      }`}
    >
      <span
        className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
          checked ? 'translate-x-[18px]' : 'translate-x-[3px]'
        }`}
      />
    </button>
  );
}

function Select({
  value,
  onChange,
  options,
}: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-sm text-surface-200 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
    >
      {options.map((opt) => (
        <option key={opt.value} value={opt.value}>
          {opt.label}
        </option>
      ))}
    </select>
  );
}

export function GeneralSettings() {
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();

  useEffect(() => {
    load();
  }, [load]);

  if (!loaded) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">General</h2>
        <p className="mt-1 text-sm text-surface-500">
          App appearance and startup behaviour
        </p>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Appearance
        </h3>

        <SettingRow label="Theme" description="Application color theme">
          <Select
            value={get('ui_theme')}
            onChange={(v) => set('ui_theme', v)}
            options={[
              { value: 'dark', label: 'Dark' },
              { value: 'oled', label: 'OLED Black' },
              { value: 'light', label: 'Light' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label="Default list style"
          description="How channels and content are displayed"
        >
          <Select
            value={get('ui_list_style')}
            onChange={(v) => set('ui_list_style', v)}
            options={[
              { value: 'grid', label: 'Grid' },
              { value: 'list', label: 'List' },
              { value: 'compact', label: 'Compact List' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label="Show channel logos"
          description="Display logos next to channel names"
        >
          <Toggle
            checked={getBool('ui_channel_logos')}
            onChange={(v) => setBool('ui_channel_logos', v)}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Startup
        </h3>

        <SettingRow
          label="Start page"
          description="Page shown when the app launches"
        >
          <Select
            value={get('ui_start_page')}
            onChange={(v) => set('ui_start_page', v)}
            options={[
              { value: 'live', label: 'Live TV' },
              { value: 'movies', label: 'Movies' },
              { value: 'series', label: 'Series' },
              { value: 'guide', label: 'TV Guide' },
              { value: 'favorites', label: 'Favorites' },
              { value: 'history', label: 'History' },
            ]}
          />
        </SettingRow>

        <SettingRow
          label="Remember last channel"
          description="Resume the last watched channel on startup"
        >
          <Toggle
            checked={getBool('ui_remember_last_channel')}
            onChange={(v) => setBool('ui_remember_last_channel', v)}
          />
        </SettingRow>

        <SettingRow
          label="Confirm on exit"
          description="Show a confirmation dialog when closing the app"
        >
          <Toggle
            checked={getBool('ui_confirm_on_exit')}
            onChange={(v) => setBool('ui_confirm_on_exit', v)}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Interface
        </h3>

        <SettingRow
          label="Show clock in sidebar"
          description="Display the current time in the navigation sidebar"
        >
          <Toggle
            checked={getBool('ui_show_clock')}
            onChange={(v) => setBool('ui_show_clock', v)}
          />
        </SettingRow>
      </div>
    </div>
  );
}
