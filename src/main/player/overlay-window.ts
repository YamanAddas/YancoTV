import { app, BrowserWindow } from 'electron';
import path from 'path';
import log from 'electron-log/main';
import { DEV_RENDERER_URL } from '../../shared/constants';

let overlay: BrowserWindow | null = null;
let parent: BrowserWindow | null = null;
let syncScheduled = false;
let isOverlayActive = false;
let parentListeners: Array<{ event: string; handler: (...args: unknown[]) => void }> = [];

const isDev = !app.isPackaged;

/**
 * Create the transparent, frameless overlay window used to render player
 * controls on top of the embedded mpv video. The overlay is parented to the
 * main window so it moves, resizes, minimizes, and closes alongside it.
 *
 * The overlay is hidden by default — it becomes visible only when theater
 * mode is active (i.e. mpv is playing inside the main window via --wid).
 */
export function createOverlayWindow(parentWindow: BrowserWindow): BrowserWindow {
  if (overlay && !overlay.isDestroyed()) {
    return overlay;
  }

  parent = parentWindow;

  overlay = new BrowserWindow({
    parent: parentWindow,
    frame: false,
    transparent: true,
    hasShadow: false,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    focusable: true,
    show: false,
    backgroundColor: '#00000000',
    webPreferences: {
      // overlay-window.js lives in dist/main/main/player/, but preload.js is
      // one level up at dist/main/main/preload.js. Without the `..` the preload
      // silently fails to load → window.api exists (Electron stubs it) but no
      // ipcRenderer listeners fire, so the overlay never sees player events.
      preload: path.join(__dirname, '..', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      backgroundThrottling: false,
    },
  });

  overlay.setMenuBarVisibility(false);

  // Overlay should never navigate — lock it down.
  overlay.webContents.on('will-navigate', (event) => event.preventDefault());
  overlay.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

  if (isDev) {
    overlay.loadURL(`${DEV_RENDERER_URL}/overlay.html`);
  } else {
    // overlay-window.js compiles to dist/main/main/player/overlay-window.js
    // and the renderer build lands at dist/renderer/. That's THREE levels up
    // from this file, not two — the previous `../../renderer/overlay.html`
    // resolved to dist/main/renderer/overlay.html which doesn't exist, and
    // the overlay BrowserWindow silently never loaded. Symptom: ERR_FILE_NOT
    // _FOUND in stderr + a blank overlay every time mpv played a stream.
    overlay.loadFile(path.join(__dirname, '../../../renderer/overlay.html'));
  }

  overlay.on('closed', () => {
    overlay = null;
    parent = null;
  });

  // Keep overlay pinned to the content area of the parent window.
  const scheduleSync = () => {
    if (syncScheduled) return;
    syncScheduled = true;
    setImmediate(() => {
      syncScheduled = false;
      syncOverlayBounds();
    });
  };

  const onShow = () => {
    if (overlay && overlay.isVisible()) scheduleSync();
  };
  const onMinimize = () => {
    if (overlay && !overlay.isDestroyed()) overlay.hide();
  };
  const onRestore = () => {
    if (overlay && !overlay.isDestroyed() && isOverlayActive) {
      overlay.show();
      scheduleSync();
    }
  };

  parentListeners = [
    { event: 'move', handler: scheduleSync },
    { event: 'resize', handler: scheduleSync },
    { event: 'maximize', handler: scheduleSync },
    { event: 'unmaximize', handler: scheduleSync },
    { event: 'enter-full-screen', handler: scheduleSync },
    { event: 'leave-full-screen', handler: scheduleSync },
    { event: 'show', handler: onShow },
    { event: 'minimize', handler: onMinimize },
    { event: 'restore', handler: onRestore },
  ];
  for (const { event, handler } of parentListeners) {
    parentWindow.on(event as Parameters<BrowserWindow['on']>[0], handler);
  }

  return overlay;
}

export function showOverlay(): void {
  if (!overlay || overlay.isDestroyed() || !parent || parent.isDestroyed()) return;
  isOverlayActive = true;
  syncOverlayBounds();
  overlay.showInactive();
  overlay.setAlwaysOnTop(true, 'pop-up-menu');
  // Focus the main window so keyboard shortcuts still route to the app
  parent.focus();
}

export function hideOverlay(): void {
  if (!overlay || overlay.isDestroyed()) return;
  isOverlayActive = false;
  overlay.hide();
}

export function destroyOverlay(): void {
  if (parent && !parent.isDestroyed()) {
    for (const { event, handler } of parentListeners) {
      parent.removeListener(event as Parameters<BrowserWindow['on']>[0], handler);
    }
  }
  parentListeners = [];
  if (overlay && !overlay.isDestroyed()) {
    overlay.destroy();
  }
  overlay = null;
  parent = null;
  isOverlayActive = false;
}

export function getOverlayWindow(): BrowserWindow | null {
  return overlay && !overlay.isDestroyed() ? overlay : null;
}

/**
 * Position the overlay exactly over the parent window's client area.
 * On Windows, getContentBounds excludes the titlebar/frame, which is what we
 * want — the overlay should cover only the viewport, not the title bar.
 */
function syncOverlayBounds(): void {
  if (!overlay || overlay.isDestroyed()) return;
  if (!parent || parent.isDestroyed()) return;

  try {
    const bounds = parent.getContentBounds();
    overlay.setBounds(bounds);
  } catch (err) {
    log.error('Failed to sync overlay bounds:', err);
  }
}
