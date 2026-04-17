import { create } from 'zustand';

// ---------------------------------------------------------------------------
// App Settings Store
//
// Flat key-value store that mirrors the main-process settings table.
// Components read typed getters and call `set(key, value)` to persist.
// All values are strings in storage; typed helpers handle conversion.
// ---------------------------------------------------------------------------

// Default values for every setting key — used when no persisted value exists.
const DEFAULTS: Record<string, string> = {
  // General / UI
  ui_start_page: 'live',
  ui_theme: 'dark',
  ui_channel_logos: '1',
  ui_confirm_on_exit: '0',
  ui_remember_last_channel: '1',
  ui_show_clock: '0',
  ui_list_style: 'grid',

  // Desktop integration
  general_minimize_to_tray: '0',
  general_close_to_tray: '0',

  // Playback
  playback_default_volume: '80',
  playback_buffer_size: 'auto',
  playback_aspect_ratio: 'auto',
  playback_hw_accel: '1',
  playback_resume: '1',
  playback_speed: '1.0',
  playback_subtitle_lang: 'off',
  playback_audio_lang: 'default',
  playback_deinterlace: 'auto',

  // Network
  network_proxy_enabled: '0',
  network_proxy_type: 'http',
  network_proxy_host: '',
  network_proxy_port: '',
  network_user_agent: '',
  network_connection_timeout: '30',
  network_retry_attempts: '3',
  network_prefer_ipv4: '0',

  // Playlist sync
  playlist_auto_sync_on_start: '1',
  playlist_auto_sync_interval: '12',

  // EPG auto-refresh
  epg_refresh_interval: '12',

  // Recording
  recording_max_duration_minutes: '240',
  recording_max_concurrent: '3',
  recording_directory: '',

  // Downloads
  download_max_concurrent: '2',
  download_max_file_size_gb: '50',
  download_allow_private_ips: '0',
  download_directory: '',
  download_fetch_assets: '1',
  download_extract_subtitles: '1',
  download_preferred_quality: 'auto',

  // TMDb metadata
  tmdb_enabled: '0',
  tmdb_language: 'en-US',

  // OpenSubtitles
  'opensubtitles.autoSearch': '0',

  // Subtitle appearance (mpv overrides)
  subtitle_scale: '1.0',
  subtitle_color: '#FFFFFF',
  subtitle_back_opacity: '50',

  // Advanced
  advanced_mpv_path: '',
  advanced_debug_logging: '0',
};

interface SettingsState {
  data: Record<string, string>;
  loaded: boolean;

  // Actions
  load: () => Promise<void>;
  get: (key: string) => string;
  getBool: (key: string) => boolean;
  set: (key: string, value: string) => Promise<void>;
  setBool: (key: string, value: boolean) => Promise<void>;
  setMany: (entries: Record<string, string>) => Promise<void>;
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  data: { ...DEFAULTS },
  loaded: false,

  load: async () => {
    if (!window.api?.settings) return;
    const all = await window.api.settings.getAll();
    // Merge server values over defaults
    set({ data: { ...DEFAULTS, ...all }, loaded: true });
  },

  get: (key: string) => {
    return get().data[key] ?? DEFAULTS[key] ?? '';
  },

  getBool: (key: string) => {
    return get().get(key) === '1';
  },

  set: async (key: string, value: string) => {
    // Optimistic update
    set((state) => ({ data: { ...state.data, [key]: value } }));
    if (window.api?.settings) {
      await window.api.settings.set(key, value);
    }
  },

  setBool: async (key: string, value: boolean) => {
    return get().set(key, value ? '1' : '0');
  },

  setMany: async (entries: Record<string, string>) => {
    set((state) => ({ data: { ...state.data, ...entries } }));
    if (window.api?.settings) {
      await window.api.settings.setMany(entries);
    }
  },
}));

// ---------------------------------------------------------------------------
// Typed convenience selectors
// ---------------------------------------------------------------------------

export function selectStartPage(s: SettingsState) {
  return s.data.ui_start_page ?? DEFAULTS.ui_start_page;
}
