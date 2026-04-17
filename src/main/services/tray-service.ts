import { app, BrowserWindow, Menu, Tray, nativeImage } from 'electron';
import path from 'path';
import fs from 'fs';
import log from 'electron-log/main';
import { APP_NAME } from '../../shared/constants';
import { getSetting } from './settings-service';

// ---------------------------------------------------------------------------
// Tray service — system tray icon with show/hide/quit context menu.
//
// Interaction rules:
//   • Clicking the tray icon toggles main-window visibility.
//   • The menu's "Quit YancoTV" is the only way to truly exit when the
//     `general_close_to_tray` setting is on — the main window's close
//     button just hides.
//   • The menu text reflects the current window state (show vs hide).
// ---------------------------------------------------------------------------

let tray: Tray | null = null;
let isQuitting = false;

/** Returns true once the user has opted to fully quit (tray → Quit). */
export function isAppQuitting(): boolean {
  return isQuitting;
}

/** Mark the app as quitting so close handlers stop intercepting. */
export function markAppQuitting(): void {
  isQuitting = true;
}

/**
 * Resolve the tray icon path. In packaged builds electron-builder copies
 * `src/assets/` via the `files` glob; in dev we read from the project root.
 */
function resolveIconPath(): string | null {
  const candidates = [
    path.join(app.getAppPath(), 'src', 'assets', 'icon.png'),
    path.join(process.resourcesPath ?? '', 'icon.png'),
  ];
  for (const p of candidates) {
    if (p && fs.existsSync(p)) return p;
  }
  log.warn('Tray icon not found at any expected path');
  return null;
}

function buildIcon(): Electron.NativeImage {
  const iconPath = resolveIconPath();
  if (!iconPath) return nativeImage.createEmpty();
  const img = nativeImage.createFromPath(iconPath);
  // Downsize for the tray — Windows renders 16px but HiDPI wants 32px source.
  return img.resize({ width: 32, height: 32 });
}

function toggleMainVisibility(mainWindow: BrowserWindow): void {
  if (mainWindow.isDestroyed()) return;
  if (mainWindow.isVisible() && !mainWindow.isMinimized()) {
    mainWindow.hide();
  } else {
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  }
}

function buildMenu(mainWindow: BrowserWindow): Menu {
  const visible = !mainWindow.isDestroyed() && mainWindow.isVisible() && !mainWindow.isMinimized();
  return Menu.buildFromTemplate([
    {
      label: visible ? `Hide ${APP_NAME}` : `Show ${APP_NAME}`,
      click: () => toggleMainVisibility(mainWindow),
    },
    { type: 'separator' },
    {
      label: `Quit ${APP_NAME}`,
      click: () => {
        isQuitting = true;
        app.quit();
      },
    },
  ]);
}

function refreshMenu(mainWindow: BrowserWindow): void {
  if (!tray || tray.isDestroyed()) return;
  tray.setContextMenu(buildMenu(mainWindow));
}

/**
 * Create the tray and wire window-visibility events so the context menu stays
 * accurate. Safe to call exactly once after the main window exists.
 */
export function createTray(mainWindow: BrowserWindow): void {
  if (tray) return;

  tray = new Tray(buildIcon());
  tray.setToolTip(APP_NAME);
  tray.setContextMenu(buildMenu(mainWindow));

  // Left-click the tray icon → toggle. On Linux this fires `click` only if a
  // context menu isn't set; on Windows it does both — we keep both behaviors.
  tray.on('click', () => toggleMainVisibility(mainWindow));

  const refresh = () => refreshMenu(mainWindow);
  mainWindow.on('show', refresh);
  mainWindow.on('hide', refresh);
  mainWindow.on('minimize', refresh);
  mainWindow.on('restore', refresh);
  mainWindow.once('closed', () => {
    refresh();
  });
}

/** Destroy the tray icon. Call during shutdown. */
export function destroyTray(): void {
  if (tray && !tray.isDestroyed()) {
    tray.destroy();
  }
  tray = null;
}

/** Reads the live "minimize to tray" preference. */
export function shouldMinimizeToTray(): boolean {
  return getSetting('general_minimize_to_tray') === '1';
}

/** Reads the live "close to tray" preference. */
export function shouldCloseToTray(): boolean {
  return getSetting('general_close_to_tray') === '1';
}
