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

  // The overlay window is shown/hidden by the main process. Those events
  // drive theater mode here — we don't flip mode based on anything else.
  useEffect(() => {
    if (!window.api) return;
    const offShown = window.api.player.onOverlayShown(() => {
      usePlayerStore.setState({ mode: 'theater', backend: 'mpv' });
    });
    const offHidden = window.api.player.onOverlayHidden(() => {
      usePlayerStore.setState({ mode: 'idle' });
    });
    return () => {
      offShown();
      offHidden();
    };
  }, []);

  return <PlayerContainer />;
}

async function init() {
  if (window.api) {
    try {
      const result = await window.api.player.checkMpv();
      usePlayerStore.setState({ backend: result.available ? 'mpv' : 'html5' });
    } catch {
      usePlayerStore.setState({ backend: 'mpv' });
    }
  }
  // Forward mpv IPC events to this window's store so controls reflect state.
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
