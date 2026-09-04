import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useParentalStore } from '../stores/parental-store';
import { PinModal } from '../components/PinModal';
import { useT } from '../i18n';
import { GeneralSettings } from '../components/settings/GeneralSettings';
import { PlaylistSettings } from '../components/settings/PlaylistSettings';
import { EpgSettings } from '../components/settings/EpgSettings';
import { PlaybackSettings } from '../components/settings/PlaybackSettings';
import { ParentalSettings } from '../components/settings/ParentalSettings';
import { NetworkSettings } from '../components/settings/NetworkSettings';
import { MetadataSettings } from '../components/settings/MetadataSettings';
import { RecordingSettings } from '../components/settings/RecordingSettings';
import { DownloadsSettings } from '../components/settings/DownloadsSettings';
import { SubtitlesSettings } from '../components/settings/SubtitlesSettings';
import { AdvancedSettings } from '../components/settings/AdvancedSettings';
import { ShortcutsSettings } from '../components/settings/ShortcutsSettings';
import { AboutSettings } from '../components/settings/AboutSettings';

// ---------------------------------------------------------------------------
// Settings categories
// ---------------------------------------------------------------------------

type SettingsCategory =
  | 'general'
  | 'playlists'
  | 'epg'
  | 'playback'
  | 'subtitles'
  | 'recording'
  | 'downloads'
  | 'parental'
  | 'network'
  | 'metadata'
  | 'advanced'
  | 'shortcuts'
  | 'about';

interface CategoryDef {
  id: SettingsCategory;
  label: string;
  icon: string;
}

// Stroke-path icons (Tailwind heroicons-style). Each `icon` is the `d` attribute
// of a single `<path>` element rendered inside a 24×24 viewBox.
const categories: CategoryDef[] = [
  { id: 'general', label: 'General', icon: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z' },
  { id: 'playlists', label: 'Playlists', icon: 'M4 6h16M4 10h16M4 14h16M4 18h16' },
  { id: 'epg', label: 'EPG', icon: 'M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z' },
  { id: 'playback', label: 'Playback', icon: 'M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z M21 12a9 9 0 11-18 0 9 9 0 0118 0z' },
  { id: 'subtitles', label: 'Subtitles', icon: 'M7 8h10M7 12h6M5 4h14a2 2 0 012 2v12a2 2 0 01-2 2H5a2 2 0 01-2-2V6a2 2 0 012-2z' },
  { id: 'recording', label: 'Recording', icon: 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M12 21a9 9 0 110-18 9 9 0 010 18z' },
  { id: 'downloads', label: 'Downloads', icon: 'M12 4v12m0 0l-4-4m4 4l4-4M4 20h16' },
  { id: 'parental', label: 'Parental Controls', icon: 'M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z' },
  { id: 'network', label: 'Network', icon: 'M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9' },
  { id: 'metadata', label: 'Metadata', icon: 'M4 6a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6zm4 2h8m-8 4h8m-8 4h4' },
  { id: 'advanced', label: 'Advanced', icon: 'M10.5 6h3l.5 2.25 1.9 1.1 2.1-.9 1.5 2.6-1.6 1.6.1 2.2 1.6 1.6-1.5 2.6-2.1-.9-1.9 1.1-.5 2.25h-3l-.5-2.25-1.9-1.1-2.1.9-1.5-2.6 1.6-1.6-.1-2.2-1.6-1.6 1.5-2.6 2.1.9 1.9-1.1z M14 12a2 2 0 11-4 0 2 2 0 014 0z' },
  { id: 'shortcuts', label: 'Keyboard Shortcuts', icon: 'M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17h.01' },
  { id: 'about', label: 'About', icon: 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z' },
];

// ---------------------------------------------------------------------------
// SettingsPage — glass sidebar + content
// ---------------------------------------------------------------------------

export function SettingsPage() {
  const t = useT();
  const navigate = useNavigate();
  const parental = useParentalStore((s) => s.settings);
  const parentalLoaded = useParentalStore((s) => s.loaded);
  const parentalLoad = useParentalStore((s) => s.load);
  const settingsUnlocked = useParentalStore((s) => s.settingsUnlocked);
  const unlockSettings = useParentalStore((s) => s.unlockSettings);

  // The gate has to know the real setting before deciding. Without this the
  // store's defaults (pinEnabled: false) would render the page unlocked for a
  // frame on a cold navigation, which is a gate that opens itself.
  useEffect(() => {
    if (!parentalLoaded) parentalLoad();
  }, [parentalLoaded, parentalLoad]);

  const [activeCategory, setActiveCategory] = useState<SettingsCategory>('general');

  // MB-409 — enforce "Require PIN to open Settings".
  //
  // The setting existed and was only ever written and read by the parental
  // settings panel itself; nothing gated this page on it, so the toggle did
  // nothing. Same family as MB-404 and MB-405: a parental control that was
  // configurable but never enforced.
  //
  // The PIN itself is verified in the main process, which hashes and
  // rate-limits it. `settingsUnlocked` only suppresses re-prompting for the
  // rest of the session; it never decides whether a PIN was right.
  const gateOn = parental.pinEnabled && parental.requirePinForSettings;
  // Treat "not loaded yet" as locked. Failing open here would flash the whole
  // settings surface before the gate could apply.
  const locked = !parentalLoaded || (gateOn && !settingsUnlocked);

  if (locked) {
    return (
      <PinModal
        title={t('settings.pinRequired')}
        onResult={(verified) => {
          if (verified) unlockSettings();
          else navigate(-1);
        }}
      />
    );
  }

  return (
    <div className="glass flex h-full gap-0 overflow-hidden rounded-2xl">
      {/* Sidebar */}
      <nav className="w-52 flex-shrink-0 overflow-y-auto border-r border-accent/8">
        <div className="p-3">
          <h2 className="px-2 pb-3 text-sm font-semibold uppercase tracking-wider text-accent/60">
            Settings
          </h2>
          <div className="space-y-0.5">
            {categories.map((cat) => (
              <button
                key={cat.id}
                onClick={() => setActiveCategory(cat.id)}
                className={`flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm font-medium transition-all duration-200 ${
                  activeCategory === cat.id
                    ? 'bg-accent/10 text-accent shadow-glow-sm'
                    : 'text-surface-400 hover:bg-surface-700/30 hover:text-surface-200'
                }`}
              >
                <svg
                  className="h-4 w-4 flex-shrink-0"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={1.5}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d={cat.icon} />
                </svg>
                {cat.label}
              </button>
            ))}
          </div>
        </div>
      </nav>

      {/* Content */}
      <div className="min-w-0 flex-1 overflow-y-auto p-6">
        {activeCategory === 'general' && <GeneralSettings />}
        {activeCategory === 'playlists' && <PlaylistSettings />}
        {activeCategory === 'epg' && <EpgSettings />}
        {activeCategory === 'playback' && <PlaybackSettings />}
        {activeCategory === 'subtitles' && <SubtitlesSettings />}
        {activeCategory === 'recording' && <RecordingSettings />}
        {activeCategory === 'downloads' && <DownloadsSettings />}
        {activeCategory === 'parental' && <ParentalSettings />}
        {activeCategory === 'network' && <NetworkSettings />}
        {activeCategory === 'metadata' && <MetadataSettings />}
        {activeCategory === 'advanced' && <AdvancedSettings />}
        {activeCategory === 'shortcuts' && <ShortcutsSettings />}
        {activeCategory === 'about' && <AboutSettings />}
      </div>
    </div>
  );
}
