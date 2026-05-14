# YancoTV — Agent Guide

Cross-tool rules for AI coding agents (Claude Code, Codex, Cursor, Copilot, Windsurf, Amp, Devin). Claude Code reads this via `@AGENTS.md` in `CLAUDE.md`.

## What this is

Custom IPTV app. Three sibling apps on a shared business core:

- **Desktop (Electron)** — `src/`, feature-complete (v0.2.0). TS + mpv + SQLite.
- **Android / Android TV (native Kotlin + KMP)** — `packages/android/` + `packages/shared/`. Active as of 2026-04-20.
- **iOS / iPadOS (SwiftUI + KMP)** — `packages/ios/`, post-Android-1.0.

**Frozen:** `packages/mobile/` (React Native). No new work except P0.

Shared logic runs in two parallel ports: `packages/core/` (TypeScript, for desktop) and `packages/shared/` (Kotlin Multiplatform, for Android/iOS). Mirror tests on both sides; neither is the source.

## Active plan

[PRODUCTION_PLAN_NATIVE.md](PRODUCTION_PLAN_NATIVE.md) drives all Android/iOS work. Every native commit maps to a `MK.*` milestone. See [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) for desktop. [PRODUCTION_PLAN_ANDROID.md](PRODUCTION_PLAN_ANDROID.md) and `packages/mobile/` are frozen (2026-04-20) — reference only.

## Setup

```bash
pnpm install                                # workspace install (pnpm enforced)
pnpm dev                                    # desktop: Vite + Electron on :5173
cd packages/android && ./gradlew :app:installDebug        # Android to connected device
./gradlew :shared:testDebugUnitTest                        # shared module unit tests (JVM)
./gradlew :shared:allTests                                 # all targets (JVM + iOS klib if present)
```

Fire TV target for native: `adb connect 192.168.68.56:5555` then `installDebug`. `JAVA_HOME` must point at Android Studio's JBR.

## Hard rules

1. **Shared core is pure.** No UI, no platform I/O. `packages/core/` depends only on `zod`; `packages/shared/commonMain/` has no `android.*` imports. Inject platform bits via interfaces (`HttpClient`, `Logger`) and `expect`/`actual`.
2. **One ExoPlayer, one mpv.** Android shares a single `ExoPlayer` between mini-preview and fullscreen via `PlayerView.switchTargetView()`. Desktop goes through `IPlayer` (`src/main/player/player.interface.ts`) — never call mpv directly.
3. **Credentials never plaintext.** Electron safeStorage on desktop, Android Keystore / iOS Keychain on native. No credentials in SQLite, settings files, or logs. *Consumer API keys (e.g. our OpenSubtitles `CONSUMER_API_KEY`) are NOT credentials* — they're public application identifiers analogous to an OAuth `client_id`, and per the upstream service's guidance they're expected to ship in client builds. They live in source with explicit `// gitleaks:allow` / `// nosemgrep:` markers and a comment documenting the consumer-key nature. Forks override via `OPENSUBTITLES_API_KEY` env var.
4. **Electron security is non-negotiable.** `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, all IPC typed via preload + validated with Zod, channel names in `src/shared/ipc-channels.ts`.
5. **SQLDelight is the only DB surface on native.** All timestamps are milliseconds except `watch_history.position_seconds` / `duration_seconds` (media offsets, not wall-clock).
6. **Compose TV uses `androidx.tv.material`.** Don't reuse Material3 clickables as TV focus targets.
7. **Ktor + Kotlinx Serialization on native.** No Retrofit/Moshi/Gson — they don't compile for iOS.
8. **Don't duplicate shared logic.** If it's tempting to copy code between `src/main/services/` and `packages/android/` or `packages/shared/`, extract to the right core package first and port the test with it.
9. **Build `@yancotv/core` before `pnpm dev`/`build`.** It's ESM; Node 24 rejects directory imports. Root `package.json` runs `build:core` automatically — don't break that chain.
10. **No feature work outside the active plan.** If it's not in the relevant `MK.*` or Sprint task, add it to the plan first.

## Threat model notes

### Cleartext traffic (Android)

The Android manifest sets `android:usesCleartextTraffic="true"` globally. This is **a deliberate, accepted threat-model decision** — not a regression and not a deferred fix. The yancoxplorer / mobile-readiness scanner reports it as a HIGH finding on every audit run; **that report is acknowledged**, the rationale below is the authoritative response.

- **Why it's on:** IPTV providers commonly serve plain HTTP — playlist endpoints, Xtream `player_api.php`, MPEG-TS streams, EPG XMLTV dumps. The provider host-set is user-configured at runtime, so an Android `network_security_config.xml` static allow-list isn't workable. The OS reads that XML once at `Application.onCreate` and offers no runtime mutation API — there is no platform-level mechanism to allow-list a host the user just added in Settings.
- **What this exposes:** an attacker on the same Wi-Fi can MITM provider URL traffic. Provider credentials are already in those URLs (Xtream auth is `?username=…&password=…`); cleartext doesn't make that worse.
- **What this does NOT expose:** Sentry telemetry (HTTPS-only by SDK config), Coil image fetches that target HTTPS CDNs (the common case). Adding any `http://` URL *outside of user-configured provider hosts* would expose new traffic — flag it in review.
- **Defense-in-depth track (future `MK.SEC.*` milestone, not part of audit cleanup):** an OkHttp-application-layer allow-list seeded from the `sources` table. Refuses HTTP requests to hosts that aren't in the user's source list — without flipping the manifest setting (which would break legitimate HTTP providers at the OS layer). Tracked under [bugs.md MB-203](bugs.md).
- **Audit posture:** the scanner finding is recorded as accepted-risk in [docs/security/AUDIT_NOTES.md](docs/security/AUDIT_NOTES.md). It will keep firing on every rescan because the manifest setting is structural to the IPTV use case; the AUDIT_NOTES entry is the durable record of why.

Provider URL credentials are otherwise handled by [`redactCredentials`](packages/shared/src/commonMain/kotlin/com/yancotv/shared/http/UrlRedaction.kt) at every error / log / DB / on-screen rendering site.

## Where deeper docs live

- [ARCHITECTURE.md](ARCHITECTURE.md) — process/data architecture
- [CHANGELOG.md](CHANGELOG.md) / [bugs.md](bugs.md) — desktop release + bug register
- [docs/adr/](docs/adr/) — architecture decisions (e.g. native pivot)
- [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) — frozen RN reference
