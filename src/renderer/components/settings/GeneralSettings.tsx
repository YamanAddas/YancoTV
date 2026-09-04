import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import { LOCALES, useT } from '../../i18n';

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
  const t = useT();
  const { get, getBool, set, setBool, load, loaded } = useSettingsStore();
  const [launchOnStartup, setLaunchOnStartup] = useState<boolean | null>(null);

  useEffect(() => {
    load();
  }, [load]);

  // Launch-on-startup state lives in the OS login-item registry, not our DB —
  // read it via IPC on mount and again whenever the settings panel is opened.
  useEffect(() => {
    let cancelled = false;
    window.api.app
      .getLaunchOnStartup()
      .then((enabled) => {
        if (!cancelled) setLaunchOnStartup(enabled);
      })
      .catch(() => {
        if (!cancelled) setLaunchOnStartup(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleLaunchOnStartupChange = async (next: boolean) => {
    setLaunchOnStartup(next); // optimistic
    const result = await window.api.app.setLaunchOnStartup(next);
    if (!result.ok) {
      // roll back on failure
      setLaunchOnStartup(!next);
    }
  };

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
          {t('settings.generalDesc')}
        </p>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Appearance
        </h3>

        <SettingRow label="Theme" description={t('settings.themeDesc')}>
          <Select
            value={get('ui_theme')}
            onChange={(v) => set('ui_theme', v)}
            options={[
              { value: 'dark', label: t('theme.dark') },
              { value: 'oled', label: t('theme.oled') },
              { value: 'light', label: t('theme.light') },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.listStyle')}
          description={t('settings.listStyleDesc')}
        >
          <Select
            value={get('ui_list_style')}
            onChange={(v) => set('ui_list_style', v)}
            options={[
              { value: 'grid', label: t('listStyle.grid') },
              { value: 'list', label: t('listStyle.list') },
              { value: 'compact', label: t('listStyle.compact') },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.channelLogos')}
          description={t('settings.channelLogosDesc')}
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
          label={t('settings.language')}
          description={t('settings.languageDesc')}
        >
          <Select
            value={get('ui_language')}
            onChange={(v) => set('ui_language', v)}
            // Built from the locale registry rather than a hand-written list,
            // so adding a locale to `src/renderer/i18n/` makes it selectable
            // without a second edit here that someone will forget.
            options={Object.entries(LOCALES).map(([code, meta]) => ({
              value: code,
              label: meta.label,
            }))}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.startPage')}
          description={t('settings.startPageDesc')}
        >
          <Select
            value={get('ui_start_page')}
            onChange={(v) => set('ui_start_page', v)}
            options={[
              { value: 'live', label: t('nav.liveTv') },
              { value: 'movies', label: t('nav.movies') },
              { value: 'series', label: t('nav.series') },
              { value: 'guide', label: t('nav.guide') },
              { value: 'favorites', label: t('nav.favorites') },
            ]}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.rememberChannel')}
          description={t('settings.rememberChannelDesc')}
        >
          <Toggle
            checked={getBool('ui_remember_last_channel')}
            onChange={(v) => setBool('ui_remember_last_channel', v)}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.confirmExit')}
          description={t('settings.confirmExitDesc')}
        >
          <Toggle
            checked={getBool('ui_confirm_on_exit')}
            onChange={(v) => setBool('ui_confirm_on_exit', v)}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          {t('settings.desktopIntegration')}
        </h3>

        <SettingRow
          label={t('settings.minimizeToTray')}
          description={t('settings.minimizeToTrayDesc')}
        >
          <Toggle
            checked={getBool('general_minimize_to_tray')}
            onChange={(v) => setBool('general_minimize_to_tray', v)}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.closeToTray')}
          description={t('settings.closeToTrayDesc')}
        >
          <Toggle
            checked={getBool('general_close_to_tray')}
            onChange={(v) => setBool('general_close_to_tray', v)}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.launchOnStartup')}
          description={t('settings.launchOnStartupDesc')}
        >
          <Toggle
            checked={launchOnStartup ?? false}
            onChange={handleLaunchOnStartupChange}
          />
        </SettingRow>
      </div>

      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Interface
        </h3>

        <SettingRow
          label={t('settings.showClock')}
          description={t('settings.showClockDesc')}
        >
          <Toggle
            checked={getBool('ui_show_clock')}
            onChange={(v) => setBool('ui_show_clock', v)}
          />
        </SettingRow>

        <SettingRow
          label={t('settings.autoTune')}
          description={t('settings.autoTuneDesc')}
        >
          <Toggle
            checked={getBool('ui_reminder_auto_tune')}
            onChange={(v) => setBool('ui_reminder_auto_tune', v)}
          />
        </SettingRow>
      </div>
    </div>
  );
}
