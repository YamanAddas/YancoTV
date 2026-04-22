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
./gradlew :shared:commonTest :shared:androidUnitTest       # KMP tests
```

Fire TV target for native: `adb connect 192.168.68.56:5555` then `installDebug`. `JAVA_HOME` must point at Android Studio's JBR.

## Hard rules

1. **Shared core is pure.** No UI, no platform I/O. `packages/core/` depends only on `zod`; `packages/shared/commonMain/` has no `android.*` imports. Inject platform bits via interfaces (`HttpClient`, `Logger`) and `expect`/`actual`.
2. **One ExoPlayer, one mpv.** Android shares a single `ExoPlayer` between mini-preview and fullscreen via `PlayerView.switchTargetView()`. Desktop goes through `IPlayer` (`src/main/player/player.interface.ts`) — never call mpv directly.
3. **Credentials never plaintext.** Electron safeStorage on desktop, Android Keystore / iOS Keychain on native. No credentials in SQLite, settings files, or logs.
4. **Electron security is non-negotiable.** `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`, all IPC typed via preload + validated with Zod, channel names in `src/shared/ipc-channels.ts`.
5. **SQLDelight is the only DB surface on native.** All timestamps are milliseconds except `watch_history.position_seconds` / `duration_seconds` (media offsets, not wall-clock).
6. **Compose TV uses `androidx.tv.material`.** Don't reuse Material3 clickables as TV focus targets.
7. **Ktor + Kotlinx Serialization on native.** No Retrofit/Moshi/Gson — they don't compile for iOS.
8. **Don't duplicate shared logic.** If it's tempting to copy code between `src/main/services/` and `packages/android/` or `packages/shared/`, extract to the right core package first and port the test with it.
9. **Build `@yancotv/core` before `pnpm dev`/`build`.** It's ESM; Node 24 rejects directory imports. Root `package.json` runs `build:core` automatically — don't break that chain.
10. **No feature work outside the active plan.** If it's not in the relevant `MK.*` or Sprint task, add it to the plan first.

## Where deeper docs live

- [ARCHITECTURE.md](ARCHITECTURE.md) — process/data architecture
- [CHANGELOG.md](CHANGELOG.md) / [bugs.md](bugs.md) — desktop release + bug register
- [docs/adr/](docs/adr/) — architecture decisions (e.g. native pivot)
- [packages/mobile/CLAUDE.md](packages/mobile/CLAUDE.md) — frozen RN reference
