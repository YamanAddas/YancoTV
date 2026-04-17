import { BrowserWindow } from 'electron';
import log from 'electron-log/main';

let videoWin: BrowserWindow | null = null;
let parent: BrowserWindow | null = null;
let syncScheduled = false;
let isActive = false;

/**
 * The "video stage" window — a transparent, frameless BrowserWindow that
 * exists solely to host mpv's embedded video surface.
 *
 * Why a dedicated window? When mpv is passed the Electron main window's HWND
 * via --wid, mpv's child surface is drawn as a sibling of Chromium's widget
 * HWND (Chrome_WidgetWin_1), which covers the entire client area. The result:
 * audio plays but video is hidden behind the Chromium compositor.
 *
 * Giving mpv its own dedicated top-level window (child of main, frameless,
 * transparent, empty HTML) sidesteps the z-order problem. The controls overlay
 * sits above this window. Stacking order from bottom to top:
 *   main (React UI)  <  video-window (mpv surface)  <  overlay (controls)
 */
export function createVideoWindow(parentWindow: BrowserWindow): BrowserWindow {
  if (videoWin && !videoWin.isDestroyed()) {
    return videoWin;
  }

  parent = parentWindow;

  videoWin = new BrowserWindow({
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
    focusable: false,
    show: false,
    backgroundColor: '#00000000',
    webPreferences: {
      // No preload — this window never executes app code. It exists purely as
      // a native HWND target for mpv. Loading minimal HTML with a transparent
      // body lets mpv's D3D surface show through.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      backgroundThrottling: false,
    },
  });

  videoWin.setMenuBarVisibility(false);
  videoWin.setIgnoreMouseEvents(true, { forward: true });

  videoWin.webContents.on('will-navigate', (event) => event.preventDefault());
  videoWin.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

  // Minimal blank page — no opaque content, no backdrop. Body is transparent
  // so the parent HWND surface (mpv's child window) is visible.
  videoWin.loadURL(
    'data:text/html;charset=utf-8,' +
      encodeURIComponent(
        '<!doctype html><html><head><meta charset="utf-8"><style>' +
          'html,body{margin:0;padding:0;width:100%;height:100%;' +
          'background:transparent!important;overflow:hidden;}' +
          '</style></head><body></body></html>',
      ),
  );

  videoWin.on('closed', () => {
    videoWin = null;
    parent = null;
  });

  const scheduleSync = () => {
    if (syncScheduled) return;
    syncScheduled = true;
    setImmediate(() => {
      syncScheduled = false;
      syncBounds();
    });
  };

  parentWindow.on('move', scheduleSync);
  parentWindow.on('resize', scheduleSync);
  parentWindow.on('maximize', scheduleSync);
  parentWindow.on('unmaximize', scheduleSync);
  parentWindow.on('enter-full-screen', scheduleSync);
  parentWindow.on('leave-full-screen', scheduleSync);
  parentWindow.on('show', () => {
    if (videoWin && videoWin.isVisible()) scheduleSync();
  });
  parentWindow.on('minimize', () => {
    if (videoWin && !videoWin.isDestroyed()) videoWin.hide();
  });
  parentWindow.on('restore', () => {
    if (videoWin && !videoWin.isDestroyed() && isActive) {
      videoWin.showInactive();
      scheduleSync();
    }
  });

  return videoWin;
}

export function showVideoWindow(): void {
  if (!videoWin || videoWin.isDestroyed() || !parent || parent.isDestroyed()) return;
  isActive = true;
  syncBounds();
  videoWin.showInactive();
}

export function hideVideoWindow(): void {
  if (!videoWin || videoWin.isDestroyed()) return;
  isActive = false;
  videoWin.hide();
}

export function destroyVideoWindow(): void {
  if (videoWin && !videoWin.isDestroyed()) {
    videoWin.destroy();
  }
  videoWin = null;
  parent = null;
  isActive = false;
}

export function getVideoWindow(): BrowserWindow | null {
  return videoWin && !videoWin.isDestroyed() ? videoWin : null;
}

/**
 * Return the native HWND of the video window as a decimal string — this is
 * what we pass to mpv via --wid so mpv embeds its video surface here instead
 * of in the main window (whose Chromium compositor would hide it).
 */
export function getVideoWindowHandle(): string | null {
  if (!videoWin || videoWin.isDestroyed()) return null;
  try {
    const handle = videoWin.getNativeWindowHandle();
    if (process.platform === 'win32') {
      return handle.readBigUInt64LE(0).toString();
    }
    return handle.readUInt32LE(0).toString();
  } catch (err) {
    log.error('Failed to get video window handle:', err);
    return null;
  }
}

function syncBounds(): void {
  if (!videoWin || videoWin.isDestroyed()) return;
  if (!parent || parent.isDestroyed()) return;
  try {
    const bounds = parent.getContentBounds();
    videoWin.setBounds(bounds);
  } catch (err) {
    log.error('Failed to sync video window bounds:', err);
  }
}
