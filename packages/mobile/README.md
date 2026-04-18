# @yancotv/mobile

Android TV + Google TV + Fire TV + Android phone/tablet client for YancoTV, built on React Native TV.

This is the Phase 1 scaffold. The JS/TS layer is ready. The native Android project is not yet generated — that's the first bootstrap step.

## Prerequisites

- **Node.js 20+**, **pnpm 9+**
- **Java JDK 17** (for Android build)
- **Android Studio** (SDK + platform tools)
- **Android SDK Platform 34** or newer
- Device or emulator:
  - Phone: any Android 8+ emulator
  - TV: Android TV emulator or real Fire TV / Chromecast with Google TV
- `ANDROID_HOME` env var set, `adb` on PATH

## Bootstrap (one-time)

### 1. Install JS dependencies

From the workspace root:

```bash
pnpm install
```

This resolves `@yancotv/core` as a workspace link and installs React Native TV and all mobile deps.

### 2. Generate the native Android project

The mobile package ships without `android/` — it needs to be generated against the installed `react-native-tvos` version to avoid version drift. Run:

```bash
cd packages/mobile
npx @react-native-community/cli@latest init YancoTVMobileNative \
  --version npm:react-native-tvos@0.76.1-0 \
  --template react-native-template-typescript \
  --skip-install \
  --directory .native-bootstrap
```

Then copy only the `android/` directory out of `.native-bootstrap` into this package root and delete the rest:

```bash
cp -r .native-bootstrap/android ./android
rm -rf .native-bootstrap
```

Edit `android/app/build.gradle`:
- Set `applicationId` to `com.yancotv.mobile`
- Add `android.defaultConfig.minSdkVersion = 23` (Fire TV Stick compatibility)

Edit `android/app/src/main/AndroidManifest.xml`:
- Add `<uses-feature android:name="android.software.leanback" android:required="false" />`
- Add `<uses-feature android:name="android.hardware.touchscreen" android:required="false" />`
- Add the Leanback launcher intent filter to `MainActivity`:
  ```xml
  <intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
  </intent-filter>
  ```

### 3. Run on a device

```bash
# Start Metro (from packages/mobile)
pnpm start

# In another terminal, build + install:
pnpm android            # phone
pnpm android:tv         # TV-focused launcher activity
```

## Dev Commands

| Command | What it does |
|---|---|
| `pnpm start` | Start Metro bundler on port 8081 |
| `pnpm android` | Build APK and install on connected device |
| `pnpm typecheck` | TypeScript-only check (fast feedback loop) |
| `pnpm lint` | ESLint over `src/` |
| `pnpm test` | Jest unit tests |

## Architecture

```
src/
├── navigation/          React Navigation stacks (Home → LiveTv → Player)
├── screens/             Top-level route components
├── components/
│   ├── tv/              TV-optimized components (D-pad focus, scale-on-focus)
│   └── phone/           Phone-optimized touch components (added in Phase 7)
├── focus/               Focus primitive + spatial navigation helpers
├── player/              react-native-video player (Phase 4)
├── db/                  op-sqlite + migrations (Phase 1.3)
├── services/            Platform glue: Keystore, notifications, etc.
├── stores/              Zustand stores (Phase 2)
└── styles/              NativeWind global.css
```

## Shared Code

All platform-agnostic business logic lives in [`@yancotv/core`](../core) — parsers, API clients, types, schemas. Import from `@yancotv/core`, not from `../../core/src/...`.

## Phase Status

- ✅ 1.1 — RN TV JS scaffold (this commit)
- ⏭️ 1.2 — Install deps, run first build, verify Hello World on Fire TV
- ⏭️ 1.3 — op-sqlite integration
- ⏭️ 1.4 — Zustand stores
- ⏭️ 1.5 — react-native-video POC
- ⏭️ 1.6 — Signed debug APK, real-device smoke test
- ⏭️ 1.7 — Sentry + error boundary

See [`PRODUCTION_PLAN_ANDROID.md`](../../PRODUCTION_PLAN_ANDROID.md) for the full roadmap.
