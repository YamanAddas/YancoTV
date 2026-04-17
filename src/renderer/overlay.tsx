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
    const offShown = window.api.player.onOverlayShown(() => {
      usePlayerStore.setState({ mode: 'theater', backend: 'mpv' });
    });
    return offShown;
  }, []);

  return <PlayerContainer />;
}

// Seed synchronously for the first-run case before any IPC events flow.
usePlayerStore.setState({ mode: 'theater', backend: 'mpv' });

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
