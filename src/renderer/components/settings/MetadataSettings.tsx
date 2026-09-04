import { useEffect, useState } from 'react';
import { useSettingsStore } from '../../stores/settings-store';
import { useT } from '../../i18n';
import type { StringKey } from '../../i18n/locales/en';

// ---------------------------------------------------------------------------
// Metadata Settings — TMDb API key, language, cache controls
//
// The TMDb API key is NOT stored in the settings store (it is encrypted in
// the main-process settings table via safeStorage). The renderer only asks
// the main process whether a key is configured, and sends new keys via IPC.
// ---------------------------------------------------------------------------

// Keys, not resolved labels: a module-level constant is evaluated once at
// import, so a resolved string would freeze the language active at load.
const LANGUAGE_OPTIONS = [
  { value: 'en-US', labelKey: 'lang.englishUs' as StringKey },
  { value: 'en-GB', labelKey: 'lang.englishUk' as StringKey },
  { value: 'es-ES', labelKey: 'lang.spanish' as StringKey },
  { value: 'fr-FR', labelKey: 'lang.french' as StringKey },
  { value: 'de-DE', labelKey: 'lang.german' as StringKey },
  { value: 'it-IT', labelKey: 'lang.italian' as StringKey },
  { value: 'pt-BR', labelKey: 'lang.portugueseBr' as StringKey },
  { value: 'ar-SA', labelKey: 'lang.arabic' as StringKey },
  { value: 'ja-JP', labelKey: 'lang.japanese' as StringKey },
  { value: 'ko-KR', labelKey: 'lang.korean' as StringKey },
  { value: 'tr-TR', labelKey: 'lang.turkish' as StringKey },
  { value: 'ru-RU', labelKey: 'lang.russian' as StringKey },
];

export function MetadataSettings() {
  const t = useT();
  const { getBool, get, setBool, set, load, loaded } = useSettingsStore();

  const [hasApiKey, setHasApiKey] = useState<boolean | null>(null);
  const [apiKeyInput, setApiKeyInput] = useState('');
  const [testing, setTesting] = useState(false);
  const [testMessage, setTestMessage] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const [saving, setSaving] = useState(false);

  // Load settings + probe for existing API key
  useEffect(() => {
    load();
    refreshStatus();
  }, [load]);

  async function refreshStatus() {
    if (!window.api?.tmdb) return;
    const status = await window.api.tmdb.getStatus();
    setHasApiKey(status.hasApiKey);
  }

  async function handleSaveKey() {
    const trimmed = apiKeyInput.trim();
    if (!trimmed) return;
    setSaving(true);
    setTestMessage(null);
    try {
      const result = await window.api.tmdb.setApiKey(trimmed);
      if (result.ok) {
        setApiKeyInput('');
        setTestMessage({ kind: 'ok', text: 'API key saved.' });
        await refreshStatus();
      } else {
        setTestMessage({ kind: 'err', text: result.error || 'Failed to save key.' });
      }
    } finally {
      setSaving(false);
    }
  }

  async function handleTestKey() {
    const trimmed = apiKeyInput.trim();
    if (!trimmed) return;
    setTesting(true);
    setTestMessage(null);
    try {
      const result = await window.api.tmdb.testApiKey(trimmed);
      if (result.ok) {
        setTestMessage({ kind: 'ok', text: 'Key works — TMDb accepted it.' });
      } else {
        setTestMessage({ kind: 'err', text: result.error || 'Key rejected by TMDb.' });
      }
    } finally {
      setTesting(false);
    }
  }

  async function handleClearKey() {
    await window.api.tmdb.clearApiKey();
    await refreshStatus();
    setTestMessage({ kind: 'ok', text: 'API key removed.' });
  }

  async function handleClearCache() {
    await window.api.tmdb.clearCache();
    setTestMessage({ kind: 'ok', text: 'Cached TMDb lookups cleared.' });
  }

  if (!loaded || hasApiKey === null) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">{t('settingsTab.metadata')}</h2>
        <p className="mt-1 text-sm text-surface-500">
          Enrich movies and series with posters, plots, cast, and ratings from TMDb.
        </p>
      </div>

      {/* Enable toggle */}
      <div className="rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
        <div className="flex items-center justify-between gap-4">
          <div>
            <p className="text-sm font-medium text-surface-200">{t('metadata.enableTmdb')}</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Requires a free API key from themoviedb.org
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={getBool('tmdb_enabled')}
            onClick={() => setBool('tmdb_enabled', !getBool('tmdb_enabled'))}
            className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
              getBool('tmdb_enabled') ? 'bg-accent shadow-glow-sm' : 'bg-surface-600'
            }`}
          >
            <span
              className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white transition-transform ${
                getBool('tmdb_enabled') ? 'translate-x-[18px]' : 'translate-x-[3px]'
              }`}
            />
          </button>
        </div>
      </div>

      {/* API key */}
      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          API Key
        </h3>

        <div className="rounded-xl border border-accent/5 bg-surface-900/30 p-4 space-y-3">
          <div>
            <p className="text-sm font-medium text-surface-200">
              TMDb API key (v3 auth)
            </p>
            <p className="mt-0.5 text-xs text-surface-500">
              {hasApiKey
                ? 'An API key is saved. Enter a new one to replace it, or clear it below.'
                : 'No key saved yet. Get a free key at themoviedb.org → Settings → API.'}
            </p>
          </div>

          <div className="flex gap-2">
            <input
              type="password"
              value={apiKeyInput}
              onChange={(e) => setApiKeyInput(e.target.value)}
              placeholder={hasApiKey ? '••••••••••••••••••••' : 'Paste your TMDb API key'}
              autoComplete="off"
              className="flex-1 rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 outline-none transition-colors focus:border-accent/50 focus:ring-1 focus:ring-accent/30"
            />
          </div>

          <div className="flex flex-wrap gap-2">
            <button
              onClick={handleSaveKey}
              disabled={!apiKeyInput.trim() || saving}
              className="rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-surface-950 transition-colors hover:bg-accent-hover disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save key'}
            </button>
            <button
              onClick={handleTestKey}
              disabled={!apiKeyInput.trim() || testing}
              className="rounded-lg border border-surface-700 bg-surface-800/40 px-3 py-1.5 text-sm font-medium text-surface-300 transition-colors hover:border-accent/40 hover:text-accent disabled:opacity-50"
            >
              {testing ? 'Testing…' : 'Test key'}
            </button>
            {hasApiKey && (
              <button
                onClick={handleClearKey}
                className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-1.5 text-sm font-medium text-red-400 transition-colors hover:bg-red-500/20"
              >
                {t('metadata.clearKey')}
              </button>
            )}
          </div>

          {testMessage && (
            <p
              className={`text-xs ${
                testMessage.kind === 'ok' ? 'text-emerald-400' : 'text-red-400'
              }`}
            >
              {testMessage.text}
            </p>
          )}
        </div>
      </div>

      {/* Language */}
      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Preferences
        </h3>

        <div className="flex items-center justify-between gap-4 rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div>
            <p className="text-sm font-medium text-surface-200">{t('settings.language')}</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Plot, titles, and tagline translations
            </p>
          </div>
          <select
            value={get('tmdb_language')}
            onChange={(e) => set('tmdb_language', e.target.value)}
            className="rounded-lg border border-surface-700/50 bg-surface-800/40 px-3 py-1.5 text-sm text-surface-200 focus:border-accent/50 focus:outline-none focus:ring-1 focus:ring-accent/30"
          >
            {LANGUAGE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {t(opt.labelKey)}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Cache */}
      <div className="space-y-2">
        <h3 className="px-1 text-xs font-semibold uppercase tracking-wider text-surface-500">
          Cache
        </h3>

        <div className="flex items-center justify-between gap-4 rounded-xl border border-accent/5 bg-surface-900/30 px-4 py-3">
          <div>
            <p className="text-sm font-medium text-surface-200">{t('metadata.clearCache')}</p>
            <p className="mt-0.5 text-xs text-surface-500">
              Forces YancoTV to re-fetch metadata next time a detail page opens.
            </p>
          </div>
          <button
            onClick={handleClearCache}
            className="rounded-lg border border-surface-700 bg-surface-800/40 px-3 py-1.5 text-sm font-medium text-surface-300 transition-colors hover:border-accent/40 hover:text-accent"
          >
            {t('metadata.clearCacheShort')}
          </button>
        </div>
      </div>
    </div>
  );
}
