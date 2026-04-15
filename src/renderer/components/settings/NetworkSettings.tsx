import { useEffect } from 'react';
import { useSettingsStore } from '../../stores/settings-store';

// ---------------------------------------------------------------------------
// Network Settings — proxy, user-agent, timeout, connection preferences
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
    <div className="flex items-center justify-between gap-4 rounded-lg border border-surface-800 bg-surface-900/60 px-4 py-3">
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
      className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${
        checked ? 'bg-accent' : 'bg-surface-600'
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

export function NetworkSettings() {
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

  const proxyEnabled = getBool('network_proxy_enabled');

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-surface-100">Network</h2>
        <p className="mt-1 text-sm text-surface-500">
          Connection and proxy settings for streaming
        </p>
      </div>

      {/* Proxy */}
      <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold uppercase tracking-wider text-surface-500">
              Proxy
            </h3>
            <p className="mt-1 text-xs text-surface-500">
              Route stream traffic through a proxy server
            </p>
          </div>
          <Toggle
            checked={proxyEnabled}
            onChange={(v) => setBool('network_proxy_enabled', v)}
          />
        </div>

        {proxyEnabled && (
          <div className="space-y-3 border-t border-surface-800 pt-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-surface-300">
                Proxy type
              </label>
              <select
                value={get('network_proxy_type')}
                onChange={(e) => set('network_proxy_type', e.target.value)}
                className="rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-200 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
              >
                <option value="http">HTTP</option>
                <option value="https">HTTPS</option>
                <option value="socks5">SOCKS5</option>
              </select>
            </div>

            <div className="flex gap-3">
              <div className="flex-1">
                <label className="mb-1 block text-sm font-medium text-surface-300">
                  Host
                </label>
                <input
                  type="text"
                  value={get('network_proxy_host')}
                  onChange={(e) => set('network_proxy_host', e.target.value)}
                  placeholder="proxy.example.com"
                  className="w-full rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div className="w-24">
                <label className="mb-1 block text-sm font-medium text-surface-300">
                  Port
                </label>
                <input
                  type="text"
                  value={get('network_proxy_port')}
                  onChange={(e) =>
                    set('network_proxy_port', e.target.value.replace(/\D/g, ''))
                  }
                  placeholder="8080"
                  className="w-full rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          </div>
        )}
      </section>

      {/* Connection settings */}
      <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          Connection
        </h3>
        <div className="space-y-2">
          <SettingRow
            label="Connection timeout"
            description="Seconds to wait before a stream connection is considered failed"
          >
            <select
              value={get('network_connection_timeout')}
              onChange={(e) => set('network_connection_timeout', e.target.value)}
              className="rounded-lg border border-surface-700 bg-surface-800 px-3 py-1.5 text-sm text-surface-200 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            >
              <option value="10">10 seconds</option>
              <option value="20">20 seconds</option>
              <option value="30">30 seconds</option>
              <option value="60">60 seconds</option>
              <option value="90">90 seconds</option>
            </select>
          </SettingRow>

          <SettingRow
            label="Retry attempts"
            description="Number of times to retry a failed stream connection"
          >
            <select
              value={get('network_retry_attempts')}
              onChange={(e) => set('network_retry_attempts', e.target.value)}
              className="rounded-lg border border-surface-700 bg-surface-800 px-3 py-1.5 text-sm text-surface-200 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            >
              <option value="0">No retry</option>
              <option value="1">1 attempt</option>
              <option value="2">2 attempts</option>
              <option value="3">3 attempts</option>
              <option value="5">5 attempts</option>
            </select>
          </SettingRow>

          <SettingRow
            label="Prefer IPv4"
            description="Force IPv4 connections (helps with some providers)"
          >
            <Toggle
              checked={getBool('network_prefer_ipv4')}
              onChange={(v) => setBool('network_prefer_ipv4', v)}
            />
          </SettingRow>
        </div>
      </section>

      {/* User-Agent */}
      <section className="rounded-xl border border-surface-800 bg-surface-900 p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-surface-500">
          Advanced
        </h3>
        <div>
          <label className="mb-1 block text-sm font-medium text-surface-300">
            Custom User-Agent
          </label>
          <p className="mb-2 text-xs text-surface-500">
            Override the User-Agent header sent with stream requests. Leave
            empty to use the default.
          </p>
          <input
            type="text"
            value={get('network_user_agent')}
            onChange={(e) => set('network_user_agent', e.target.value)}
            placeholder="Mozilla/5.0 (Windows NT 10.0; Win64; x64) ..."
            className="w-full rounded-lg border border-surface-700 bg-surface-800 px-3 py-2 text-sm text-surface-200 placeholder-surface-500 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </div>
      </section>
    </div>
  );
}
