# YancoTV — Bug Register

Living register of **open** bugs across desktop and mobile. Closed bugs move to the changelog; incident reports (deep post-mortems or one-off refactor bug dumps) go to [docs/incidents/](docs/incidents/).

**Last updated:** 2026-04-20
**Format:** ID · Platform · Severity · One-line · Status · First seen · Fix target

| ID | Platform | Sev | Summary | Status | First seen | Fix target |
|---|---|---|---|---|---|---|
| MB-13 | Mobile | High | Player takes two back presses to close; surface state desyncs between routes | **Fixed** | 2026-04-12 | Collapsed to one route + state-driven fullscreen (2026-04-20) — no modal to pop, back toggles `isFullscreen` then stops |
| MB-14 | Mobile | High | HEVC-main10 / AC3 / EAC3 / DTS / TrueHD decode audio-only on ~30% of streams | Open | 2026-04-10 | M8R (FFmpeg ExoPlayer extension, vendored jniLibs) |
| MB-15 | Mobile | Medium | First-frame blocked by hydration gate on cold boot | Open | 2026-04-14 | M4R.6 (cached-first boot) |
| MB-16 | Mobile | High | SearchScreen crashes during fast typing | **Fixed** | 2026-04-14 | FlatList virtualization (2026-04-19) — full rebuild as SearchOverlay in M4R |
| MB-17 | Mobile | Medium | Navigation sluggish across the whole app | In flight | 2026-04-12 | M4R (paged SQL + collapsed navigator + CachedImage) |
| MB-18 | Desktop | Critical | Electron boot `ERR_UNSUPPORTED_DIR_IMPORT` / `ERR_MODULE_NOT_FOUND` on `@yancotv/core` internal imports | **Fixed** | 2026-04-19 | `.js`-extension commit (`e454da4`, 2026-04-19) type-checked but didn't run on Electron's bundled Node 24; real fix builds `@yancotv/core` to `dist/` via tsc and points `main`/`exports` at the emitted JS (2026-04-20) |
| DB-01 | Desktop | High | Overlay Back button stops playback but main window stays in theater mode → blank screen, no sidebar/content | **Fixed** | 2026-04-20 | `src/renderer/stores/player-store.ts` state-change handler now treats mpv `status: 'stopped'` the same as `'idle'` and exits theater mode (2026-04-20) |
| MB-19 | Mobile | Critical | PhoneLayout crams 260-wide LeftRail into 56px horizontal strip — categories + Sources button unreachable | Open | 2026-04-19 | New task — PhoneShell component |
| MB-20 | Mobile | High | `Platform.isTV` misdetects on some Fire TV / GTV boxes → those devices fall into broken PhoneLayout | Open | 2026-04-19 | New task — robust `isTelevision()` helper (UiMode + feature flag) |
| MB-21 | Mobile | High | No SafeAreaView / status bar inset on HomeShell — top content covered on notched phones | Open | 2026-04-19 | New task — wrap in SafeAreaView with proper insets |
| MB-22 | Mobile | Medium | `ContentPanel.tsx:56` dead conditional (`category.kind === 'type' ? category.type : category.type` — both branches identical) | Open | 2026-04-19 | Fix inline |
| MB-23 | Mobile | Medium | No `hasTVPreferredFocus` on first mount — D-pad does nothing until click | Open | 2026-04-19 | M4R.10 (focus primitive) |
| MB-24 | Mobile | High | Visual design doesn't match desktop — flat tiles, no hex logo frames, no quality badges, no category filter column, sidebar only has 4 content types (not full global nav) | Open | 2026-04-19 | M4R.D (design parity block) |
| MB-25 | Mobile | Critical | Native `ReactExoplayerView` / `TextureView` measures `0` height on Fire TV, causing black video while audio plays | **Fixed** | 2026-04-20 | M4R.Player (`09150e9`, 2026-04-20) — react-native-video removed entirely; replaced by native Android `PlayerActivity` hosting Media3 ExoPlayer directly. RN bridge no longer in the playback path. Picture renders on Fire TV v7a + phone arm64 |
| MB-26 | Mobile | High | Fullscreen route renders controls only and depends on underlying persistent player; collapsed host leaves black fullscreen playback | **Fixed** | 2026-04-20 | M4R.Player (`09150e9`) — `FullscreenPlayer` RN route deleted; playback is a native AppCompat Activity launched via Intent. No RN fullscreen host exists |
| MB-27 | Mobile | High | `transparentModal` fullscreen overlay plus persistent player underneath makes video surface layout/composition fragile on TV | **Fixed** | 2026-04-20 | M4R.Player (`09150e9`) — no `transparentModal`, no persistent RN `<Video>`. Native Activity owns the whole Window while playing |
| MB-28 | Mobile | High | `react-native-video` `viewType={TEXTURE}` is ineffective because local Android `updateSurfaceView(viewType)` is a no-op | **Fixed** | 2026-04-20 | M4R.Player (`09150e9`) — react-native-video dropped; native `PlayerView` uses stock Media3 SurfaceView |
| MB-29 | Mobile | Medium | Patched `exo_player_view_texture.xml` uses invalid Android XML namespace `res-android` instead of `res/android` | **Fixed** | 2026-04-20 | M4R.Player (`09150e9`) — `patches/react-native-video@6.19.1.patch` deleted, `patchedDependencies` removed from root `package.json`, `react-native-video` removed from mobile deps |
| MB-30 | Mobile | High | No bundled Media3 FFmpeg extension / `jniLibs`; DTS, TrueHD, and other non-platform codecs still fail on Fire TV-class devices | Open | 2026-04-20 | M8R (FFmpeg ExoPlayer extension, vendored jniLibs) |
| MB-31 | Mobile | Medium | Release APK installed on Fire TV as `armeabi-v7a`, so future native decoder extensions must ship matching 32-bit libraries | Open | 2026-04-20 | M8R (ABI split validation) |
| MB-32 | Mobile | Medium | IPTV stream type detection relies mostly on URL suffixes, missing extensionless/query-based Xtream/HLS/MPEG-TS streams | Open | 2026-04-20 | M4R.7 (source metadata and stream probing) |
| MB-33 | Mobile | Medium | Player has no visible unsupported-video-track state, so decoder failures and render-surface failures both look like generic black playback | Open | 2026-04-20 | M7R (player diagnostics and error UI) |
| MB-34 | Mobile | Low | Player diagnostics are mostly dev-only/Sentry breadcrumbs, leaving release ADB logs without enough codec/layout detail | Open | 2026-04-20 | M7R (release-safe playback telemetry) |

## How to use this register

- **Adding a bug:** append a row with the next MB-NN (mobile) or DB-NN (desktop) ID. Keep summary to one line; details go in the fix commit.
- **Fixing a bug:** flip Status to **Fixed**, add commit ref to the Fix target column, and remove the row on the next doc pass once the fix has stabilized.
- **Dumping a refactor-wide set of bugs:** don't list them here. Create `docs/incidents/YYYY-MM-DD-<topic>.md` and link it from the Status column (e.g. `Open — see [incident](docs/incidents/...)`).

## Cross-references

- [docs/incidents/2026-04-16-html5-player-refactor.md](docs/incidents/2026-04-16-html5-player-refactor.md) — desktop HTML5 refactor bug dump (bugs 1–29, archived)
- [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) § Reboot Notice — M4R rebuilds the mobile shell; MB-13/15/17/19–23 are all scoped there
