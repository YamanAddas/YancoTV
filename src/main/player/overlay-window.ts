import { app, BrowserWindow } from 'electron';
import path from 'path';
import log from 'electron-log/main';

let overlay: BrowserWindow | null = null;
let parent: BrowserWindow | null = null;
let syncScheduled = false;
let isOverlayActive = false;

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
      preload: path.join(__dirname, 'preload.js'),
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
    overlay.loadURL('http://localhost:5173/overlay.html');
  } else {
    overlay.loadFile(path.join(__dirname, '../../renderer/overlay.html'));
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

  parentWindow.on('move', scheduleSync);
  parentWindow.on('resize', scheduleSync);
  parentWindow.on('maximize', scheduleSync);
  parentWindow.on('unmaximize', scheduleSync);
  parentWindow.on('enter-full-screen', scheduleSync);
  parentWindow.on('leave-full-screen', scheduleSync);
  parentWindow.on('show', () => {
    if (overlay && overlay.isVisible()) scheduleSync();
  });
  parentWindow.on('minimize', () => {
    if (overlay && !overlay.isDestroyed()) overlay.hide();
  });
  parentWindow.on('restore', () => {
    if (overlay && !overlay.isDestroyed() && isOverlayActive) {
      overlay.show();
      scheduleSync();
    }
  });

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
