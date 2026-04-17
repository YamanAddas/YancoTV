import { useToastStore } from './stores/toast-store';

// ---------------------------------------------------------------------------
// Renderer-side crash handler — catches runtime errors that escape React's
// render tree and forwards them to the main process log file. Keeping both
// sides' errors in one file is the whole point of the "Export logs…" button.
//
// Three entry points:
//   - window 'error'               → uncaught throws in event handlers, timers, etc.
//   - window 'unhandledrejection'  → rejected promises with no .catch()
//   - <ErrorBoundary>              → React render/commit errors (wired in App.tsx)
// ---------------------------------------------------------------------------

let installed = false;

function showCrashToast(summary: string): void {
  try {
    useToastStore.getState().push({
      kind: 'error',
      message: `Something went wrong: ${summary}`,
      durationMs: 6000,
    });
  } catch {
    // Toast store may not be ready during very early startup failures.
  }
}

function report(report: {
  message: string;
  stack?: string;
  source?: string;
  line?: number;
  col?: number;
  kind: 'error' | 'unhandledrejection' | 'react';
}): void {
  try {
    void window.api?.app.reportCrash(report);
  } catch {
    // Preload not installed yet, or IPC torn down. Nothing we can do.
  }
}

export function installRendererCrashHandlers(): void {
  if (installed) return;
  installed = true;

  window.addEventListener('error', (event) => {
    const err = event.error;
    const message = err instanceof Error ? err.message : event.message || 'Unknown error';
    report({
      kind: 'error',
      message,
      stack: err instanceof Error ? err.stack : undefined,
      source: event.filename,
      line: event.lineno,
      col: event.colno,
    });
    showCrashToast(message);
  });

  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason;
    const message = reason instanceof Error ? reason.message : String(reason ?? 'Unhandled rejection');
    report({
      kind: 'unhandledrejection',
      message,
      stack: reason instanceof Error ? reason.stack : undefined,
    });
    showCrashToast(message);
  });
}

/** Used by the React ErrorBoundary to forward render errors through the same pipe. */
export function reportReactError(err: Error, componentStack?: string): void {
  report({
    kind: 'react',
    message: err.message || 'React render error',
    stack: err.stack ? `${err.stack}\n---\n${componentStack ?? ''}` : componentStack,
  });
}
