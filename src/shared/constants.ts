export const APP_NAME = 'YancoTV';
export const APP_VERSION = '0.3.8';

export const DB_FILE_NAME = 'yancotv.db';

export const DEFAULT_WINDOW_WIDTH = 1280;
export const DEFAULT_WINDOW_HEIGHT = 800;
export const MIN_WINDOW_WIDTH = 960;
export const MIN_WINDOW_HEIGHT = 600;

/**
 * Optional URL that returns a JSON manifest describing the latest release:
 *   { "version": "0.2.0", "url"?: "https://…download…", "notes"?: "..." }
 *
 * Served from the shared releases repo's GitHub Pages site, in a
 * WINDOWS-SPECIFIC folder. Android publishes to `update.json` at the ROOT of
 * the same site and must stay there: its endpoint is baked into each APK at
 * compile time (`BuildConfig.UPDATE_ENDPOINT`), so every already-installed
 * Android app polls the root path. Moving it would silently cut those installs
 * off from updates. Desktop is new, so it starts in its own folder and the two
 * platforms never collide.
 *
 * Expected shape (see `UpdateManifest` in update-service.ts):
 *   { "version": "0.3.9", "url": "https://…/YancoTV-Setup-0.3.9.exe", "notes": "…" }
 *
 * Note this is NOT the same shape as Android's manifest, which carries
 * `versionCode` / `versionName` / `downloadUrl` / `sha256`. Two platforms, two
 * updaters, two files — deliberately, rather than one shared schema neither
 * fits.
 *
 * When empty, the manual "Check for updates" button short-circuits with a
 * "not configured" message, which is what shipped until now.
 */
export const UPDATE_MANIFEST_URL =
  'https://yamanaddas.github.io/yancotv-releases/windows/update.json';

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
