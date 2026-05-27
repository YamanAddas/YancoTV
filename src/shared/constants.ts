export const APP_NAME = 'YancoTV';
export const APP_VERSION = '0.3.4';

export const DB_FILE_NAME = 'yancotv.db';

export const DEFAULT_WINDOW_WIDTH = 1280;
export const DEFAULT_WINDOW_HEIGHT = 800;
export const MIN_WINDOW_WIDTH = 960;
export const MIN_WINDOW_HEIGHT = 600;

/**
 * Optional URL that returns a JSON manifest describing the latest release:
 *   { "version": "0.2.0", "url"?: "https://…download…", "notes"?: "..." }
 *
 * When empty, the manual "Check for updates" button short-circuits with a
 * "not configured" message. The auto-update work (Sprint 18.3) will set this
 * once release infra lands.
 */
export const UPDATE_MANIFEST_URL = '';

/**
 * Dev server URL used by Electron's main process to load the renderer when
 * `isDev` is true. Vite serves at this address by default (see vite.config
 * `server.port`). All `isDev`-gated `loadURL` / navigation guards reference
 * this single constant so the port is consistent across `mainWindow`,
 * `overlay-window`, and any future renderer surface.
 *
 * Production builds load `file://` resources from the packaged dist
 * folder and never touch this URL.
 */
export const DEV_RENDERER_URL = 'http://localhost:5173';
