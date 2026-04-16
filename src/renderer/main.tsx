import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { usePlayerStore, initPlayerEventListeners } from './stores/player-store';
import { useFavoritesStore } from './stores/favorites-store';
import './styles/global.css';

// --- Async initialization ---
async function init() {
  // Check mpv availability and set the player backend accordingly
  if (window.api) {
    try {
      const result = await window.api.player.checkMpv();
      usePlayerStore.setState({ backend: result.available ? 'mpv' : 'html5' });
    } catch {
      usePlayerStore.setState({ backend: 'html5' });
    }
  }

  // Subscribe to player events (mpv IPC push + history tracking)
  initPlayerEventListeners();

  // Load favorites into memory for O(1) lookup across the app
  useFavoritesStore.getState().load();
}

init();

const root = document.getElementById('root');
if (!root) throw new Error('Root element not found');

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
