import { StrictMode, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { PlayerContainer } from './components/player/PlayerContainer';
import { usePlayerStore, initPlayerEventListeners } from './stores/player-store';
import { usePlayerShortcuts } from './hooks/use-player-shortcuts';
import './styles/global.css';

/**
 * Overlay renderer — runs inside the transparent, frameless BrowserWindow
 * that sits above the embedded mpv video. Displays ONLY the player controls
 * (TheaterControls + SettingsPanel via PlayerContainer). No sidebar, no
 * routing, no browsing UI.
 */
function OverlayApp() {
  usePlayerShortcuts();

  // The overlay is shown/hidden by the main process whenever a stream starts
  // or stops. Re-seed mode='theater' on every show so the PlayerContainer
  // gate always renders when we're visible — the shared player-store's stop()
  // action flips mode to 'idle' when mpv idles between streams, which would
  // otherwise leave the overlay blank on the *next* playback.
  useEffect(() => {
    if (!window.api) return;
    const offShown = window.api.player.onOverlayShown((media) => {
      usePlayerStore.setState({
        mode: 'theater',
        backend: 'mpv',
        currentUrl: media?.url,
        currentTitle: media?.title,
        currentContentId: media?.contentId,
      });
    });
    return offShown;
  }, []);

  return <PlayerContainer />;
}

// Backend is mpv by definition for the overlay (it only exists when an mpv
// stream is embedded). Mode starts as 'idle' and flips to 'theater' when the
// main process actually shows the overlay (PLAYER_OVERLAY_SHOWN listener
// below, plus the PLAYER_MODE_BROADCAST sync); seeding 'theater' here used to
// kick PlayerContainer's setPresentation effect on overlay-window load and
// caused a blank, full-screen overlay on cold app start.
usePlayerStore.setState({ backend: 'mpv' });

async function init() {
  if (window.api) {
    try {
      const result = await window.api.player.checkMpv();
      usePlayerStore.setState({ backend: result.available ? 'mpv' : 'html5' });
    } catch {
      usePlayerStore.setState({ backend: 'mpv' });
    }
  }
  initPlayerEventListeners();
}

init();

const root = document.getElementById('root');
if (!root) throw new Error('Root element not found');

createRoot(root).render(
  <StrictMode>
    <OverlayApp />
  </StrictMode>,
);
