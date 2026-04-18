import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ScreenRouter } from './src/navigation/ScreenRouter';
import { useSourcesStore } from './src/stores/sources-store';
import { Sentry } from './src/sentry';
import { initDatabase, type InitDbResult } from './src/db/db';

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

function HydrationGate({ children }: { children: React.ReactNode }) {
  const hydrated = useSourcesStore((s) => s.hydrated);
  const hydrate = useSourcesStore((s) => s.hydrate);
  const [hydrateError, setHydrateError] = useState<string | null>(null);
  const [dbInit, setDbInit] = useState<InitDbResult | null>(null);
  const [dbError, setDbError] = useState<string | null>(null);

  useEffect(() => {
    initDatabase()
      .then((r) => setDbInit(r))
      .catch((e: unknown) => {
        const msg = e instanceof Error ? `${e.message}\n${e.stack ?? ''}` : String(e);
        setDbError(msg);
        Sentry.captureException(e);
      });
  }, []);

  useEffect(() => {
    hydrate().catch((e: unknown) => {
      const msg = e instanceof Error ? `${e.message}\n${e.stack ?? ''}` : String(e);
      setHydrateError(msg);
      Sentry.captureException(e);
    });
  }, [hydrate]);

  if (dbError) {
    return (
      <ScrollView style={bootStyles.errorScroll}>
        <StatusBar barStyle="light-content" backgroundColor="#1a0000" />
        <Text style={bootStyles.errorTitle}>Database init failed</Text>
        <Text style={bootStyles.errorStack}>{dbError}</Text>
      </ScrollView>
    );
  }

  if (hydrateError) {
    return (
      <ScrollView style={bootStyles.errorScroll}>
        <StatusBar barStyle="light-content" backgroundColor="#1a0000" />
        <Text style={bootStyles.errorTitle}>Hydration failed</Text>
        <Text style={bootStyles.errorStack}>{hydrateError}</Text>
      </ScrollView>
    );
  }

  if (!hydrated || !dbInit) {
    return (
      <View style={bootStyles.loadingRoot}>
        <StatusBar barStyle="light-content" backgroundColor="#0a0a0f" />
        <ActivityIndicator size="large" color="#fbbf24" />
        <Text style={bootStyles.loadingText}>
          {dbInit ? 'Loading (BUILD 2)…' : 'Opening database…'}
        </Text>
        {dbInit && (
          <Text style={bootStyles.loadingText}>
            sqlite {dbInit.version} · {dbInit.applied.length} migration(s) applied
          </Text>
        )}
      </View>
    );
  }

  return <>{children}</>;
}

function App() {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    const id = setTimeout(() => setMounted(true), 800);
    return () => clearTimeout(id);
  }, []);

  if (!mounted) {
    return (
      <View style={bootStyles.splash}>
        <StatusBar barStyle="light-content" backgroundColor="#004d26" />
        <Text style={bootStyles.splashTitle}>YancoTV BUILD 2</Text>
        <Text style={bootStyles.splashSub}>JS bundle loaded ✓</Text>
      </View>
    );
  }

  return (
    <RootErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <HydrationGate>
          <ScreenRouter />
        </HydrationGate>
      </QueryClientProvider>
    </RootErrorBoundary>
  );
}

const bootStyles = StyleSheet.create({
  splash: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#004d26',
  },
  splashTitle: {
    color: '#ffffff',
    fontSize: 36,
    fontWeight: '800',
  },
  splashSub: {
    color: '#a7f3d0',
    fontSize: 18,
    marginTop: 12,
  },
  loadingRoot: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#0a0a0f',
  },
  loadingText: {
    color: '#9ca3af',
    marginTop: 16,
    fontSize: 14,
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
