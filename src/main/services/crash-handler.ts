import { app, type BrowserWindow } from 'electron';
import log from 'electron-log/main';

// ---------------------------------------------------------------------------
// Crash handler — catch every kind of failure Electron will otherwise swallow
// silently, so bug reports (and the log export button) have something to go on.
//
// We handle four classes of failure:
//   1. uncaughtException / unhandledRejection in the main process
//   2. render-process-gone (the renderer crashed, not a JS error — the whole
//      web contents died, e.g. OOM)
//   3. child-process-gone (utility/GPU/pepper children)
//   4. Renderer-side errors reported over IPC (wired in ipc/index.ts)
//
// Policy: log everything, don't exit. The user's work is in the renderer —
// killing the main process on every uncaughtException would surprise users
// more than the underlying bug does.
// ---------------------------------------------------------------------------

function formatError(err: unknown): string {
  if (err instanceof Error) {
    return `${err.name}: ${err.message}${err.stack ? `\n${err.stack}` : ''}`;
  }
  try {
    return JSON.stringify(err);
  } catch {
    return String(err);
  }
}

export interface CrashReport {
  message: string;
  stack?: string;
  source?: string;
  line?: number;
  col?: number;
  kind?: 'error' | 'unhandledrejection' | 'react';
}

/**
 * Log a crash report arriving from the renderer. The main-process log file
 * is the single place the user is told to grab when reporting bugs, so we
 * fold renderer errors into it rather than writing a separate file.
 */
export function logRendererCrash(report: CrashReport): void {
  const kind = report.kind ?? 'error';
  const prefix = `[renderer/${kind}]`;
  const location = report.source
    ? ` at ${report.source}${report.line ? `:${report.line}:${report.col ?? 0}` : ''}`
    : '';
  log.error(`${prefix} ${report.message}${location}`);
  if (report.stack) {
    log.error(report.stack);
  }
}

export function installMainCrashHandlers(getMain: () => BrowserWindow | null): void {
  process.on('uncaughtException', (err) => {
    log.error('[main/uncaughtException]', formatError(err));
  });

  process.on('unhandledRejection', (reason) => {
    log.error('[main/unhandledRejection]', formatError(reason));
  });

  // Renderer process died — not a JS error, the whole web contents crashed.
  // Reloading is the only sensible recovery; the user's view state is gone
  // either way.
  app.on('render-process-gone', (_event, webContents, details) => {
    log.error(
      `[render-process-gone] reason=${details.reason} exitCode=${details.exitCode}`,
    );
    const main = getMain();
    if (main && !main.isDestroyed() && webContents === main.webContents) {
      // All reasons in the Electron type union are failure modes
      // (killed/crashed/oom/etc.), so reloading is always the right call.
      try {
        main.reload();
      } catch (err) {
        log.error('Failed to reload main window after render-process-gone:', err);
      }
    }
  });

  // Child processes: GPU, utility workers, etc. Rarely actionable but worth
  // a log line so `grep child-process-gone` actually returns something.
  app.on('child-process-gone', (_event, details) => {
    log.warn(
      `[child-process-gone] type=${details.type} reason=${details.reason} exitCode=${details.exitCode} name=${details.name ?? ''}`,
    );
  });
}
