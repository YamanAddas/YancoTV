import fs from 'fs';
import { execFileSync } from 'child_process';
import log from 'electron-log/main';

let cachedPath: string | null | undefined;

/**
 * Locate the ffmpeg binary. The `ffmpeg-static` package ships a platform-specific
 * binary with the app — in a packaged build, electron-builder's `asarUnpack` rule
 * extracts it to `app.asar.unpacked/` and Electron's asar shim rewrites the path
 * automatically, so `require('ffmpeg-static')` just works in both dev and prod.
 *
 * System PATH is kept as a last-resort fallback for power users who want to
 * point at a newer/custom ffmpeg via an env var or global install.
 */
export function findFfmpegPath(): string | null {
  if (cachedPath !== undefined) return cachedPath;

  try {
    // The rule was renamed: `no-var-requires` became `no-require-imports` in
    // typescript-eslint v8, so the old disable comment stopped suppressing
    // anything. The require itself is deliberate — `ffmpeg-static` resolves a
    // path at call time and must not be hoisted into a static import, which
    // would break the packaged app when the binary is absent.
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const bundled: string | null = require('ffmpeg-static');
    if (bundled && fs.existsSync(bundled)) {
      log.info(`ffmpeg (bundled): ${bundled}`);
      cachedPath = bundled;
      return bundled;
    }
  } catch (err) {
    log.warn(`ffmpeg-static resolution failed: ${String(err)}`);
  }

  try {
    const cmd = process.platform === 'win32' ? 'where' : 'which';
    const bin = process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg';
    const result = execFileSync(cmd, [bin], {
      encoding: 'utf8',
      timeout: 5000,
      windowsHide: true,
    });
    const firstLine = result.trim().split('\n')[0]?.trim();
    if (firstLine && fs.existsSync(firstLine)) {
      log.info(`ffmpeg (PATH): ${firstLine}`);
      cachedPath = firstLine;
      return firstLine;
    }
  } catch {
    // Not in PATH — falls through to null.
  }

  log.warn('ffmpeg not found (neither bundled nor on PATH)');
  cachedPath = null;
  return null;
}
