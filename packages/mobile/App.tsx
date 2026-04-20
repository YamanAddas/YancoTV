import React, { useEffect } from 'react';
import { ScrollView, StatusBar, StyleSheet, Text } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RootNavigator } from './src/navigation/RootNavigator';
import { useSourcesStore } from './src/stores/sources-store';
import { useRecentChannelsStore } from './src/stores/recent-channels-store';
import { useFavoritesStore } from './src/stores/favorites-store';
import { useHistoryStore } from './src/stores/history-store';
import { useSearchHistoryStore } from './src/stores/search-history-store';
import { useBootStore } from './src/stores/boot-store';
import { Sentry } from './src/sentry';
import { initDatabase } from './src/db/db';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 60_000, retry: 1 },
  },
});

interface ErrorBoundaryState {
  error: Error | null;
  info: string | null;
}

class RootErrorBoundary extends React.Component<
  { children: React.ReactNode },
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { error: null, info: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error, info: null };
  }

  componentDidCatch(error: Error, info: { componentStack?: string }) {
    this.setState({ error, info: info.componentStack ?? null });
    Sentry.captureException(error, {
      contexts: { react: { componentStack: info.componentStack ?? '' } },
    });
  }

  render() {
    if (this.state.error) {
      return (
        <ScrollView style={bootStyles.errorScroll}>
          <StatusBar barStyle="light-content" backgroundColor="#1a0000" />
          <Text style={bootStyles.errorTitle}>App crashed</Text>
          <Text style={bootStyles.errorMessage}>
            {this.state.error.message || String(this.state.error)}
          </Text>
          {this.state.error.stack && (
            <Text style={bootStyles.errorStack}>{this.state.error.stack}</Text>
          )}
          {this.state.info && (
            <Text style={bootStyles.errorStack}>{this.state.info}</Text>
          )}
        </ScrollView>
      );
    }
    return <>{this.props.children}</>;
  }
}

// Cached-first boot (M4R.6, rule 6). Open the DB, flip `dbReady`, then
// kick off every hydrate path in the background. The shell is already
// mounted by the time any of this runs — panels render their empty state
// until SQLite is open and consumers re-query on `dbReady` flip.
//
// Only a true DB-open failure blocks paint; hydrate failures surface via
// Sentry and the UI degrades to empty collections.
function BackgroundBoot() {
  useEffect(() => {
    let cancelled = false;
    initDatabase()
      .then(() => {
        if (cancelled) return;
        useBootStore.getState().setDbReady(true);
        void useSourcesStore
          .getState()
          .hydrate()
          .catch((e: unknown) => Sentry.captureException(e));
        // Factory-store hydrates: call via `.getState()` because pnpm
        // workspaces can produce distinct zustand module identities and
        // the hook form throws "Invalid hook call" across that boundary.
        void useRecentChannelsStore
          .getState()
          .hydrate()
          .catch((e: unknown) => Sentry.captureException(e));
        void useFavoritesStore
          .getState()
          .load()
          .catch((e: unknown) => Sentry.captureException(e));
        void useHistoryStore
          .getState()
          .load()
          .catch((e: unknown) => Sentry.captureException(e));
        void useSearchHistoryStore
          .getState()
          .load()
          .catch((e: unknown) => Sentry.captureException(e));
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        const msg = e instanceof Error ? `${e.message}\n${e.stack ?? ''}` : String(e);
        useBootStore.getState().setDbError(msg);
        Sentry.captureException(e);
      });
    return () => {
      cancelled = true;
    };
  }, []);
  return null;
}

function DbErrorScreen({ message }: { message: string }) {
  return (
    <ScrollView style={bootStyles.errorScroll}>
      <StatusBar barStyle="light-content" backgroundColor="#1a0000" />
      <Text style={bootStyles.errorTitle}>Database init failed</Text>
      <Text style={bootStyles.errorStack}>{message}</Text>
    </ScrollView>
  );
}

function BootGate({ children }: { children: React.ReactNode }) {
  const dbError = useBootStore((s) => s.dbError);
  if (dbError) return <DbErrorScreen message={dbError} />;
  return <>{children}</>;
}

function App() {
  return (
    <RootErrorBoundary>
      <GestureHandlerRootView style={bootStyles.rootFlex}>
        <SafeAreaProvider>
          <QueryClientProvider client={queryClient}>
            <BackgroundBoot />
            <BootGate>
              <RootNavigator />
            </BootGate>
          </QueryClientProvider>
        </SafeAreaProvider>
      </GestureHandlerRootView>
    </RootErrorBoundary>
  );
}

const bootStyles = StyleSheet.create({
  rootFlex: {
    flex: 1,
  },
  errorScroll: {
    flex: 1,
    backgroundColor: '#1a0000',
    padding: 24,
  },
  errorTitle: {
    color: '#fca5a5',
    fontSize: 28,
    fontWeight: '800',
    marginTop: 48,
    marginBottom: 16,
  },
  errorMessage: {
    color: '#fff',
    fontSize: 16,
    marginBottom: 24,
    lineHeight: 22,
  },
  errorStack: {
    color: '#fecaca',
    fontSize: 11,
    fontFamily: 'monospace',
    marginBottom: 24,
  },
});

export default Sentry.wrap(App);
