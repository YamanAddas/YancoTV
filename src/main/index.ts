import { app, BrowserWindow, session } from 'electron';
import path from 'path';
import fs from 'fs';
import log from 'electron-log/main';
import {
  APP_NAME,
  DEFAULT_WINDOW_HEIGHT,
  DEFAULT_WINDOW_WIDTH,
  DEV_RENDERER_URL,
  MIN_WINDOW_HEIGHT,
  MIN_WINDOW_WIDTH,
} from '../shared/constants';
import { initDatabase, closeDatabase } from './services/db';
import { getSetting } from './services/settings-service';
import { registerIpcHandlers, destroyPlayer } from './ipc';
import { startAutoRefresh, stopAutoRefresh } from './services/epg-service';
import { startAutoSync, stopAutoSync } from './services/source-sync';
import { startReminderService, stopReminderService } from './services/reminder-service';
import { reconcileOnStartup as reconcileRecordings, stopAllOnQuit as stopAllRecordings } from './services/recording-service';
import { reconcileOnStartup as reconcileDownloads, stopAllOnQuit as stopAllDownloads } from './services/download-service';
import { createOverlayWindow, destroyOverlay, getOverlayWindow } from './player/overlay-window';
import {
  createVideoWindow,
  destroyVideoWindow,
  getVideoWindow,
  getVideoWindowHandle,
} from './player/video-window';
import {
  createTray,
  destroyTray,
  isAppQuitting,
  markAppQuitting,
  shouldCloseToTray,
  shouldMinimizeToTray,
} from './services/tray-service';
import { installMainCrashHandlers } from './services/crash-handler';

// electron-builder's portable target sets PORTABLE_EXECUTABLE_DIR to the
// directory the user launched the .exe from. When present, redirect userData
// and logs into a "YancoTV-Data" folder next to the exe so the build is
// actually portable — no footprint in %APPDATA%, DB and credentials travel
// with the exe. Must run BEFORE log.initialize() and before any other code
// that resolves app.getPath('userData').
const portableRoot = process.env.PORTABLE_EXECUTABLE_DIR;
if (portableRoot) {
  const dataDir = path.join(portableRoot, 'YancoTV-Data');
  try {
    fs.mkdirSync(dataDir, { recursive: true });
    app.setPath('userData', dataDir);
    app.setPath('logs', path.join(dataDir, 'logs'));
  } catch (err) {
    // If the user launched the portable exe from a read-only location, fall
    // back to the default userData path rather than crashing — they'll get a
    // non-portable install but the app still works.
    console.error('Portable mode: failed to redirect userData, using default', err);
  }
}

log.initialize();
log.info(`${APP_NAME} starting...`);
if (portableRoot) {
  log.info(`Portable mode: userData = ${app.getPath('userData')}`);
}

// Install crash handlers as early as possible so any failure during app
// bootstrap lands in the log file instead of a silent exit.
installMainCrashHandlers(() => mainWindow);

let mainWindow: BrowserWindow | null = null;

// E2E runs launch dist/main/main/index.js directly (not a packaged bundle),
// so app.isPackaged is false and the renderer would try to load from the
// Vite dev server. Treat NODE_ENV=test as "load from disk" so Playwright
// sees the built YancoTV renderer, not whatever else is on :5173.
const isDev = !app.isPackaged && process.env.NODE_ENV !== 'test';

export function getMainWindow(): BrowserWindow | null {
  return mainWindow;
}

/** Accessor re-exported for IPC handlers that need to push events to the overlay. */
export { getOverlayWindow };

/** Accessor re-exported for IPC handlers that need to embed mpv into the video window. */
export { getVideoWindow, getVideoWindowHandle };

/**
 * Get the native window handle (HWND on Windows) as a decimal string.
 * Used to embed mpv as a child window via --wid.
 */
export function getMainWindowHandle(): string | null {
  if (!mainWindow || mainWindow.isDestroyed()) return null;
  try {
    return readHandleBuffer(mainWindow.getNativeWindowHandle());
  } catch (err) {
    log.error('Failed to get native window handle:', err);
    return null;
  }
}

/**
 * Resolve the app icon for BrowserWindow. Prefers `.ico` on Windows (renders
 * crisply at taskbar/titlebar sizes); falls back to `.png` on other platforms
 * or if the ico is missing. In packaged builds electron-builder copies
 * `src/assets/` via the `files` glob; in dev we read from the project root.
 */
function resolveAppIconPath(): string | undefined {
  const file = process.platform === 'win32' ? 'icon.ico' : 'icon.png';
  const candidates = [
    path.join(app.getAppPath(), 'src', 'assets', file),
    path.join(process.resourcesPath ?? '', file),
  ];
  for (const p of candidates) {
    if (p && fs.existsSync(p)) return p;
  }
  log.warn('App icon not found at any expected path');
  return undefined;
}

function readHandleBuffer(buf: Buffer): string | null {
  // On 64-bit Windows, HWND is 8 bytes. On 32-bit Windows/Linux/macOS, 4 bytes.
  // Electron sometimes returns a shorter buffer than the platform pointer
  // width — read by actual length, not platform assumption.
  if (process.platform === 'win32' && buf.length >= 8) {
    return buf.readBigUInt64LE(0).toString();
  }
  if (buf.length >= 4) {
    return buf.readUInt32LE(0).toString();
  }
  return null;
}

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: DEFAULT_WINDOW_WIDTH,
    height: DEFAULT_WINDOW_HEIGHT,
    minWidth: MIN_WINDOW_WIDTH,
    minHeight: MIN_WINDOW_HEIGHT,
    title: APP_NAME,
    icon: resolveAppIconPath(),
    backgroundColor: '#0f172a',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      backgroundThrottling: false,
    },
  });

  // Create the video stage + controls overlay child windows BEFORE the
  // renderer has a chance to fire PLAYER_PLAY. They were originally created
  // in `ready-to-show`, which fires after the renderer's first paint —
  // which is after React's useEffects, including the auto-play that
  // dispatches PLAYER_PLAY. If PLAYER_PLAY arrived before ready-to-show,
  // `getVideoWindowHandle()` returned null and mpv fell through to its
  // standalone window (no embedded video, no overlay controls — the exact
  // "I see mpv in a separate window with no controls" symptom).
  //
  // Both child windows are created with `show: false`, so they're invisible
  // until the renderer's MiniPlayer or PlayerContainer drives them via the
  // PLAYER_SET_PRESENTATION / PLAYER_SET_VIDEO_BOUNDS IPCs.
  createVideoWindow(mainWindow);
  createOverlayWindow(mainWindow);

  mainWindow.on('ready-to-show', () => {
    mainWindow?.show();
    // Tray icon needs the main window for context-menu actions ("Show
    // YancoTV" / "Quit") but doesn't need any child windows, so it can stay
    // in ready-to-show.
    if (mainWindow) {
      createTray(mainWindow);
    }
  });

  // Minimize → tray: Electron's 'minimize' event is non-preventable — minimize
  // has already happened by the time it fires. We react by hiding the window,
  // which removes it from the taskbar; the tray icon is the way back.
  mainWindow.on('minimize', () => {
    if (shouldMinimizeToTray() && mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.hide();
    }
  });

  // Close → tray: keep the app running in the background when the user clicks
  // the red X. The tray's "Quit" menu still exits cleanly.
  mainWindow.on('close', (event) => {
    if (!isAppQuitting() && shouldCloseToTray() && mainWindow) {
      event.preventDefault();
      mainWindow.hide();
    }
  });

  mainWindow.on('closed', () => {
    destroyOverlay();
    destroyVideoWindow();
    mainWindow = null;
  });

  // Block navigation to external URLs — prevent renderer hijacking
  mainWindow.webContents.on('will-navigate', (event, url) => {
    const allowed = isDev ? DEV_RENDERER_URL : 'file://';
    if (!url.startsWith(allowed)) {
      log.warn(`Blocked navigation to: ${url}`);
      event.preventDefault();
    }
  });

  // Block new window creation (e.g. window.open, target="_blank")
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    log.warn(`Blocked new window for: ${url}`);
    return { action: 'deny' };
  });

  if (isDev) {
    mainWindow.loadURL(DEV_RENDERER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, '../../renderer/index.html'));
  }
}

app.whenReady().then(() => {
  log.info('App ready, initializing...');

  // Windows groups taskbar entries by AppUserModelID. Without this, dev runs
  // appear under Electron's default ID instead of YancoTV's icon/label.
  if (process.platform === 'win32') {
    app.setAppUserModelId('com.yancotv.app');
  }

  // --- CORS bypass for IPTV streams ---
  // Strip the Origin header from outgoing requests to external URLs.
  // This prevents Chromium from applying CORS checks entirely — the
  // request looks same-origin to the browser. Safe in a desktop app.
  session.defaultSession.webRequest.onBeforeSendHeaders((details, callback) => {
    const headers = { ...details.requestHeaders };
    // Only strip Origin for external requests (not localhost/self)
    if (details.url.startsWith('http') && !details.url.includes('localhost')) {
      delete headers['Origin'];
      delete headers['origin'];
    }
    callback({ requestHeaders: headers });
  });

  // CSP + CORS response headers
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    const csp = isDev
      ? "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: http:; media-src 'self' https: http: blob:; connect-src 'self' https: http: ws://localhost:*; font-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'self'; worker-src 'self' blob:"
      : "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https: http:; media-src 'self' https: http: blob:; connect-src 'self' https: http:; font-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'self'; worker-src 'self' blob:";
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Content-Security-Policy': [csp],
        'Access-Control-Allow-Origin': ['*'],
        'Access-Control-Allow-Headers': ['Range, Content-Type'],
        'Access-Control-Expose-Headers': ['Content-Range, Content-Length'],
      },
    });
  });

  initDatabase();

  // Honor Settings → Advanced → Debug logging. Default level is 'info';
  // flipping debug on turns on the full verbose trail in both file + console.
  const debugLogging = getSetting('advanced_debug_logging') === '1';
  log.transports.file.level = debugLogging ? 'debug' : 'info';
  log.transports.console.level = debugLogging ? 'debug' : 'info';

  reconcileRecordings();
  reconcileDownloads();
  registerIpcHandlers();
  startAutoRefresh(12); // Refresh EPG every 12 hours
  startAutoSync(); // Check for sources needing re-sync every 5 minutes
  startReminderService(); // Scan for reminders coming due; fire within ~1s accuracy
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

// Any time `app.quit()` is initiated (tray menu, platform quit, etc.) mark the
// flag so the main window's close handler stops intercepting.
app.on('before-quit', () => {
  markAppQuitting();
});

app.on('window-all-closed', () => {
  stopAutoRefresh();
  stopAutoSync();
  stopReminderService();
  stopAllRecordings();
  stopAllDownloads();
  destroyPlayer();
  destroyOverlay();
  destroyVideoWindow();
  destroyTray();
  closeDatabase();
  app.quit();
});
