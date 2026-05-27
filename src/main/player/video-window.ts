import { BrowserWindow, type Rectangle } from 'electron';
import log from 'electron-log/main';

let videoWin: BrowserWindow | null = null;
let parent: BrowserWindow | null = null;
let syncScheduled = false;
let isActive = false;
let parentListeners: Array<{ event: string; handler: (...args: unknown[]) => void }> = [];
/**
 * When set, the video child window positions over this rect (renderer-relative,
 * DIPs) on every sync — but only when `presentationMode === 'mini'`. Theater
 * mode ignores it and uses the full parent content area, so we can preserve
 * the last-known mini rect across a theater detour and restore it on the
 * next minimize without a renderer round-trip.
 */
let customBounds: Rectangle | null = null;
/**
 * Tracks which presentation the mpv child window is currently serving. Drives
 * syncBounds: 'mini' honours customBounds (if set), 'theater' always uses the
 * full parent content area, 'idle' is the no-stream state.
 *
 * Maintained by setPresentation() in ipc/index.ts; never set by the renderer.
 */
let presentationMode: 'theater' | 'mini' | 'idle' = 'idle';

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
 * Set a renderer-relative bounding rect for the embedded mpv video child
 * window. Used by the docked mini-player so mpv's surface paints into the
 * card instead of covering the full content area. Pass null to restore
 * full-content tracking (theater mode).
 *
 * Re-syncs immediately so the change is visible without waiting for the
 * next parent move/resize event.
 */
export function setVideoWindowBounds(rect: Rectangle | null): void {
  customBounds = rect;
  if (videoWin && !videoWin.isDestroyed() && parent && !parent.isDestroyed() && isActive) {
    syncBounds();
  }
}

export function getVideoWindowCustomBounds(): Rectangle | null {
  return customBounds;
}

/**
 * Set the presentation mode that drives syncBounds' choice between
 * customBounds (mini) and the full parent content area (theater). Re-syncs
 * immediately so the change is visible without waiting for the next parent
 * move/resize event.
 *
 * Idempotent — repeating the same mode is a no-op apart from a single
 * re-sync, which itself is a no-op if the bounds haven't moved.
 */
export function setVideoWindowPresentationMode(mode: 'theater' | 'mini' | 'idle'): void {
  presentationMode = mode;
  if (videoWin && !videoWin.isDestroyed() && parent && !parent.isDestroyed() && isActive) {
    syncBounds();
  }
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
  try {
    const parentBounds = parent.getContentBounds();
    if (presentationMode === 'mini' && customBounds) {
      // Translate renderer-relative DIPs into absolute screen DIPs. Electron's
      // BrowserWindow APIs all work in DIPs (logical pixels) — CSS pixels in
      // the renderer match 1:1, so no DPR scaling needed here.
      // Clamp to the parent content area so a stale or off-page rect (e.g.
      // mid-resize) doesn't fling the video window onto another monitor.
      const x = Math.max(parentBounds.x, parentBounds.x + Math.round(customBounds.x));
      const y = Math.max(parentBounds.y, parentBounds.y + Math.round(customBounds.y));
      const maxW = parentBounds.x + parentBounds.width - x;
      const maxH = parentBounds.y + parentBounds.height - y;
      videoWin.setBounds({
        x,
        y,
        width: Math.max(1, Math.min(maxW, Math.round(customBounds.width))),
        height: Math.max(1, Math.min(maxH, Math.round(customBounds.height))),
      });
    } else {
      // theater (or no customBounds) — full content area.
      videoWin.setBounds(parentBounds);
    }
  } catch (err) {
    log.error('Failed to sync video window bounds:', err);
  }
}
