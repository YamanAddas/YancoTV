import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initPlayerEventListeners } from './stores/player-store';
import { useFavoritesStore } from './stores/favorites-store';
import './styles/global.css';

// Subscribe to player events from main process
initPlayerEventListeners();

// Load favorites into memory for O(1) lookup across the app
useFavoritesStore.getState().load();

const root = document.getElementById('root');
if (!root) throw new Error('Root element not found');

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
