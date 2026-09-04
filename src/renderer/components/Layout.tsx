import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { PlayerContainer } from './player/PlayerContainer';
import { VideoStage } from './player/VideoStage';
import { MiniPlayer } from './player/MiniPlayer';
import { Toaster } from './Toaster';
import { ZapOverlay } from './ZapOverlay';
import { usePlayerShortcuts } from '../hooks/use-player-shortcuts';
import { useChannelZap } from '../hooks/use-channel-zap';
import { useGamepad } from '../hooks/use-gamepad';
import { usePlayerStore } from '../stores/player-store';
import { useParentalStore } from '../stores/parental-store';
import { PinModal } from './PinModal';
import { useT } from '../i18n';

export function Layout() {
  const t = useT();
  usePlayerShortcuts();
  useChannelZap();
  useGamepad();

  // MB-405 — the app's one PIN prompt for locked playback. It lives here rather
  // than on a page because playback starts from eight places (pages, channel
  // zap, reminders, keyboard shortcuts, autoplay-on-launch); a per-page modal is
  // exactly how the gate ended up existing on the Live TV grid alone.
  const pendingUnlock = useParentalStore((s) => s.pendingUnlock);
  const resolveUnlock = useParentalStore((s) => s.resolveUnlock);

  const mode = usePlayerStore((s) => s.mode);
  const backend = usePlayerStore((s) => s.backend);
  const isTheater = mode === 'theater';
  const isMini = mode === 'mini';
  // When mpv is the active backend, the video is drawn by mpv embedded into a
  // dedicated child window and the theater controls live in a separate
  // transparent overlay BrowserWindow. The main window renders only the
  // mini-player chrome (which itself just positions an empty surface for mpv).
  const renderPlayerContainer = backend !== 'mpv';

  return (
    <div className="relative flex h-screen w-screen overflow-hidden bg-space">
      {/* Subtle star-like dots via CSS — static for performance */}
      {!isTheater && (
        <div
          className="pointer-events-none absolute inset-0 opacity-30"
          style={{
            backgroundImage: `
              radial-gradient(1px 1px at 10% 20%, rgba(0, 255, 170, 0.3), transparent),
              radial-gradient(1px 1px at 30% 70%, rgba(0, 204, 255, 0.2), transparent),
              radial-gradient(1px 1px at 50% 10%, rgba(255, 255, 255, 0.2), transparent),
              radial-gradient(1px 1px at 70% 50%, rgba(0, 255, 170, 0.2), transparent),
              radial-gradient(1px 1px at 90% 80%, rgba(0, 204, 255, 0.3), transparent),
              radial-gradient(1px 1px at 20% 90%, rgba(255, 255, 255, 0.15), transparent),
              radial-gradient(1px 1px at 60% 40%, rgba(0, 255, 200, 0.2), transparent),
              radial-gradient(1px 1px at 80% 15%, rgba(255, 255, 255, 0.2), transparent),
              radial-gradient(1px 1px at 40% 55%, rgba(0, 255, 170, 0.15), transparent),
              radial-gradient(1px 1px at 15% 45%, rgba(0, 204, 255, 0.2), transparent),
              radial-gradient(1px 1px at 85% 65%, rgba(255, 255, 255, 0.15), transparent),
              radial-gradient(1px 1px at 55% 85%, rgba(0, 255, 170, 0.2), transparent)
            `,
          }}
        />
      )}

      {/* Sidebar — hidden in theater mode */}
      {!isTheater && <Sidebar />}

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Page content — hidden in theater mode */}
        {!isTheater && (
          <main className="min-w-0 flex-1 overflow-y-auto p-6">
            <Outlet />
          </main>
        )}
      </div>

      {/* Video surface (html5 backend only) — kept mounted across mini↔theater
          transitions so playback isn't interrupted when the user expands or
          minimises. Self-gates on backend + mode. */}
      <VideoStage />

      {/* Theater chrome — overlays the entire window in theater mode (controls,
          settings, error states). Mounts only for non-mpv backends because mpv
          uses a separate transparent overlay BrowserWindow for its controls.
          The container gates itself on mode==='theater'. */}
      {renderPlayerContainer && <PlayerContainer />}

      {/* Docked mini-player — shown in mini mode so the user can browse the
          app while a stream keeps playing. Mounted at Layout level (not per
          page) so it survives route navigation. */}
      {isMini && <MiniPlayer />}

      {/* Ephemeral toasts (download-started, recording errors, etc.) — also
          rendered in theater so a "recording failed to start" toast doesn't
          get swallowed when the user is mid-watch. Toaster lives at z-[1000]
          (top-right) which sits cleanly above TheaterControls (z-[45]) and
          doesn't fight with the MiniPlayer card (bottom-right). */}
      <Toaster />

      {/* Channel zap preview — shown in theater mode when PageUp/Down is pressed */}
      <ZapOverlay />

      {pendingUnlock && (
        <PinModal
          title={
            pendingUnlock.title
              ? t('parental.unlockTitle', { title: pendingUnlock.title })
              : undefined
          }
          contentId={pendingUnlock.contentId}
          onResult={resolveUnlock}
        />
      )}
    </div>
  );
}
