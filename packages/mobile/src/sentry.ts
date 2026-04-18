import * as Sentry from '@sentry/react-native';

const DSN =
  'https://f838cd9a9d97b0e990bf6566efdc095b@o4509416043118592.ingest.us.sentry.io/4511239553024000';

let initialized = false;

export function initSentry() {
  if (initialized) return;
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
