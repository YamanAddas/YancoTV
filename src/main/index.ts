import { app, BrowserWindow, session } from 'electron';
import path from 'path';
import log from 'electron-log/main';
import {
  APP_NAME,
  DEFAULT_WINDOW_HEIGHT,
  DEFAULT_WINDOW_WIDTH,
  MIN_WINDOW_HEIGHT,
  MIN_WINDOW_WIDTH,
} from '../shared/constants';
import { initDatabase, closeDatabase } from './services/db';
import { registerIpcHandlers, destroyPlayer } from './ipc';
import { startAutoRefresh, stopAutoRefresh } from './services/epg-service';
import { startAutoSync, stopAutoSync } from './services/source-sync';
import { reconcileOnStartup as reconcileRecordings, stopAllOnQuit as stopAllRecordings } from './services/recording-service';
import { createOverlayWindow, destroyOverlay, getOverlayWindow } from './player/overlay-window';
import {
  createVideoWindow,
  destroyVideoWindow,
  getVideoWindow,
  getVideoWindowHandle,
} from './player/video-window';

log.initialize();
log.info(`${APP_NAME} starting...`);

let mainWindow: BrowserWindow | null = null;

const isDev = !app.isPackaged;

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

  mainWindow.on('ready-to-show', () => {
    mainWindow?.show();
    // Create the video stage window first (mpv embeds here) and the controls
    // overlay on top of it. Both are children of main and hidden until play.
    if (mainWindow) {
      createVideoWindow(mainWindow);
      createOverlayWindow(mainWindow);
    }
  });

  mainWindow.on('closed', () => {
    destroyOverlay();
    destroyVideoWindow();
    mainWindow = null;
  });

  // Block navigation to external URLs — prevent renderer hijacking
  mainWindow.webContents.on('will-navigate', (event, url) => {
    const allowed = isDev ? 'http://localhost:5173' : 'file://';
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
    mainWindow.loadURL('http://localhost:5173');
  } else {
    mainWindow.loadFile(path.join(__dirname, '../../renderer/index.html'));
  }
}

app.whenReady().then(() => {
  log.info('App ready, initializing...');

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
  reconcileRecordings();
  registerIpcHandlers();
  startAutoRefresh(12); // Refresh EPG every 12 hours
  startAutoSync(); // Check for sources needing re-sync every 5 minutes
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  stopAutoRefresh();
  stopAutoSync();
  stopAllRecordings();
  destroyPlayer();
  destroyOverlay();
  destroyVideoWindow();
  closeDatabase();
  app.quit();
});
