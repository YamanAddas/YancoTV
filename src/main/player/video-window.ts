import { BrowserWindow, screen } from 'electron';
import log from 'electron-log/main';

let videoWin: BrowserWindow | null = null;
let parent: BrowserWindow | null = null;
let syncScheduled = false;
let isActive = false;
let pipActive = false;
let parentListeners: Array<{ event: string; handler: (...args: unknown[]) => void }> = [];

const PIP_WIDTH = 480;
const PIP_HEIGHT = 270;
const PIP_INSET = 24;

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

  const onShow = () => {
    if (videoWin && videoWin.isVisible()) scheduleSync();
  };
  const onMinimize = () => {
    if (videoWin && !videoWin.isDestroyed()) videoWin.hide();
  };
  const onRestore = () => {
    if (videoWin && !videoWin.isDestroyed() && isActive) {
      videoWin.showInactive();
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
  if (parent && !parent.isDestroyed()) {
    for (const { event, handler } of parentListeners) {
      parent.removeListener(event as Parameters<BrowserWindow['on']>[0], handler);
    }
  }
  parentListeners = [];
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
    const buf = videoWin.getNativeWindowHandle();
    if (process.platform === 'win32' && buf.length >= 8) {
      return buf.readBigUInt64LE(0).toString();
    }
    if (buf.length >= 4) {
      return buf.readUInt32LE(0).toString();
    }
    return null;
  } catch (err) {
    log.error('Failed to get video window handle:', err);
    return null;
  }
}

function syncBounds(): void {
  if (!videoWin || videoWin.isDestroyed()) return;
  if (!parent || parent.isDestroyed()) return;
  // In PIP mode the video floats free — don't mirror parent bounds, or we'd
  // immediately re-inflate to full size on the next resize event.
  if (pipActive) return;
  try {
    const bounds = parent.getContentBounds();
    videoWin.setBounds(bounds);
  } catch (err) {
    log.error('Failed to sync video window bounds:', err);
  }
}

/**
 * Shrink the video window to a floating corner thumbnail and promote it to
 * always-on-top so it stays visible while the user browses the main UI.
 * No-op if already in PIP or the window isn't active.
 */
export function enterPip(): void {
  if (!videoWin || videoWin.isDestroyed()) return;
  if (!parent || parent.isDestroyed()) return;
  if (pipActive) return;

  pipActive = true;
  try {
    // Anchor to the display containing the parent window so PIP doesn't jump
    // to the primary monitor when the app is on a secondary screen.
    const parentBounds = parent.getBounds();
    const display = screen.getDisplayMatching(parentBounds);
    const work = display.workArea;
    const x = work.x + work.width - PIP_WIDTH - PIP_INSET;
    const y = work.y + work.height - PIP_HEIGHT - PIP_INSET;
    videoWin.setBounds({ x, y, width: PIP_WIDTH, height: PIP_HEIGHT });
    videoWin.setAlwaysOnTop(true, 'floating');
  } catch (err) {
    log.error('Failed to enter PIP:', err);
  }
}

/** Return from PIP to the theater layout (mirrors parent bounds again). */
export function exitPip(): void {
  if (!videoWin || videoWin.isDestroyed()) return;
  if (!pipActive) return;
  pipActive = false;
  try {
    videoWin.setAlwaysOnTop(false);
  } catch (err) {
    log.error('Failed to clear alwaysOnTop on PIP exit:', err);
  }
  syncBounds();
}

export function isPipActive(): boolean {
  return pipActive;
}
