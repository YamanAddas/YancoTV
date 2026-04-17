import { app } from 'electron';
import path from 'path';
import fs from 'fs';
import { execFileSync } from 'child_process';
import log from 'electron-log/main';

const WINDOWS_SEARCH_PATHS = [
  () => path.join(process.cwd(), 'ffmpeg', 'ffmpeg.exe'),
  () => path.join(process.resourcesPath, 'ffmpeg', 'ffmpeg.exe'),
  () => path.join(path.dirname(app.getPath('exe')), 'ffmpeg', 'ffmpeg.exe'),
  () => 'C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe',
  () => 'C:\\ffmpeg\\bin\\ffmpeg.exe',
  () => path.join(process.env.LOCALAPPDATA ?? '', 'Programs', 'ffmpeg', 'bin', 'ffmpeg.exe'),
  () => path.join(process.env.USERPROFILE ?? '', 'scoop', 'apps', 'ffmpeg', 'current', 'bin', 'ffmpeg.exe'),
];

let cachedPath: string | null | undefined;

export function findFfmpegPath(): string | null {
  if (cachedPath !== undefined) return cachedPath;

  for (const pathFn of WINDOWS_SEARCH_PATHS) {
    const p = pathFn();
    if (p && fs.existsSync(p)) {
      log.info(`ffmpeg found at: ${p}`);
      cachedPath = p;
      return p;
    }
  }

  try {
    const result = execFileSync('where', ['ffmpeg.exe'], {
      encoding: 'utf8',
      timeout: 5000,
      windowsHide: true,
    });
    const firstLine = result.trim().split('\n')[0]?.trim();
    if (firstLine && fs.existsSync(firstLine)) {
      log.info(`ffmpeg found in PATH: ${firstLine}`);
      cachedPath = firstLine;
      return firstLine;
    }
  } catch {
    // Not in PATH
  }

  log.warn('ffmpeg not found');
  cachedPath = null;
  return null;
}
