import { Component, type ErrorInfo, type ReactNode } from 'react';
import { reportReactError } from '../crash-handler';

// ---------------------------------------------------------------------------
// Top-level error boundary. Catches render/commit errors from the React tree
// and shows a last-ditch recovery screen instead of a blank window. Render
// errors are reported through the same IPC pipe as other renderer crashes
// so they land in the main log file.
// ---------------------------------------------------------------------------

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    reportReactError(error, info.componentStack ?? undefined);
  }

  reset = (): void => {
    this.setState({ error: null });
  };

  reload = (): void => {
    window.location.reload();
  };

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div className="flex min-h-screen items-center justify-center bg-surface-950 px-6 py-10 text-surface-100">
        <div className="max-w-lg space-y-4 rounded-2xl border border-red-500/20 bg-surface-900/80 p-6 shadow-2xl">
          <h1 className="text-lg font-semibold text-red-300">Something broke</h1>
          <p className="text-sm text-surface-300">
            YancoTV hit an unexpected error and the current view couldn&apos;t render.
            The error was logged — you can export the log from Settings → Advanced.
          </p>
          <pre className="max-h-40 overflow-auto rounded-md bg-surface-950/80 p-3 text-xs text-surface-400">
            {error.message}
          </pre>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={this.reset}
              className="rounded-lg border border-surface-700 px-3 py-1.5 text-sm text-surface-200 hover:border-surface-500"
            >
              Try again
            </button>
            <button
              type="button"
              onClick={this.reload}
              className="rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-surface-950 hover:bg-accent/90"
            >
              Reload app
            </button>
          </div>
        </div>
      </div>
    );
  }
}
