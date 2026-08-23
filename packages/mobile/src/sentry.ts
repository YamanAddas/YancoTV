import * as Sentry from '@sentry/react-native';

// MB-358 — this used to be a string literal. A Sentry DSN is an ingest key
// rather than a credential (every shipped client exposes one, by design), so
// this was never a data-exposure problem — but the repository went public on
// 2026-08-22, and the native app deliberately keeps its DSN out of source in
// `packages/android/local.properties`. This was the single place that broke
// that convention.
//
// `packages/mobile/` has been frozen since 2026-04-20 and is never built, so
// there is nothing to configure: read it from the environment and no-op when
// absent. If this package is ever revived, set SENTRY_DSN at build time.
const DSN = process.env.SENTRY_DSN ?? '';

let initialized = false;

export function initSentry() {
  if (initialized) return;
  // No DSN configured — nothing to report to. Skipping beats initialising
  // Sentry with an empty string, which it treats as a hard error.
  if (!DSN) return;
  initialized = true;

  Sentry.init({
    dsn: DSN,
    // Send all errors. We're diagnosing a boot crash — we want every event.
    sampleRate: 1.0,
    // No perf tracing yet. Turn on later once the app is stable.
    tracesSampleRate: 0,
    // No session replay. Adds size, not useful for a native-boot crash.
    enableAutoSessionTracking: true,
    attachStacktrace: true,
    // Show the native crash handler breadcrumbs too.
    attachScreenshot: false,
    attachViewHierarchy: false,
    // Tag releases so we can tell one APK build from the next in the dashboard.
    // versionCode comes from android/app/build.gradle — bump it per build.
    environment: __DEV__ ? 'development' : 'production',
    debug: __DEV__,
  });
}

export { Sentry };
