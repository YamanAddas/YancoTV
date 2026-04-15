import { app } from 'electron';
import path from 'path';
import fs from 'fs';
import { execFileSync } from 'child_process';
import log from 'electron-log/main';

const WINDOWS_SEARCH_PATHS = [
  // Bundled with the app (dev mode — project root)
  () => path.join(process.cwd(), 'mpv', 'mpv.exe'),
  // Bundled with the app (packaged — extraResources)
  () => path.join(process.resourcesPath, 'mpv', 'mpv.exe'),
  // Next to the exe (portable packaging)
  () => path.join(path.dirname(app.getPath('exe')), 'mpv', 'mpv.exe'),
  // Common install locations
  () => 'C:\\Program Files\\mpv\\mpv.exe',
  () => 'C:\\Program Files (x86)\\mpv\\mpv.exe',
  () => path.join(process.env.LOCALAPPDATA ?? '', 'Programs', 'mpv', 'mpv.exe'),
  () => path.join(process.env.USERPROFILE ?? '', 'scoop', 'apps', 'mpv', 'mpv.exe'),
];

/**
 * Find the mpv executable path. Checks:
 * 1. Bundled with app
 * 2. Common Windows install locations
 * 3. System PATH
 */
export function findMpvPath(): string | null {
  // Check known paths
  for (const pathFn of WINDOWS_SEARCH_PATHS) {
    const p = pathFn();
    if (p && fs.existsSync(p)) {
      log.info(`mpv found at: ${p}`);
      return p;
    }
  }

  // Check system PATH
  try {
    const result = execFileSync('where', ['mpv.exe'], {
      encoding: 'utf8',
      timeout: 5000,
      windowsHide: true,
    });
    const firstLine = result.trim().split('\n')[0]?.trim();
    if (firstLine && fs.existsSync(firstLine)) {
      log.info(`mpv found in PATH: ${firstLine}`);
      return firstLine;
    }
  } catch {
    // Not in PATH
  }

  log.warn('mpv not found');
  return null;
}
