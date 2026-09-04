# Verification — Stage 1.2 (FFmpeg + Watchdog) and Stage 1.3 (Sentry)

End-to-end smoke tests for the components shipped in Stage 1.2 and 1.3. Every
test runs on a real Fire TV via `adb broadcast` to a debug-only receiver
(declared in `app/src/debug/AndroidManifest.xml`, never compiled into release).

**Prerequisites:**
- Fire TV connected: `adb connect 192.168.68.66:5555` (DHCP — verify with `adb devices -l`, `model:AFTDCT31`)
- Debug APK installed: `./gradlew :app:installDebug` from `packages/android/`
- App launched and running on the device (broadcasts to a stopped/killed
  process don't fire)

---

## Watchdog (Stage 1.2 / MK.9.4)

Forces the FFmpeg crash-recovery path on demand. Same release → snapshot →
rebuild → restore → emit-signal flow that fires on a real FFmpeg-package
error, just triggered synthetically.

```bash
adb -s 192.168.68.66:5555 logcat -c
adb -s 192.168.68.66:5555 shell am broadcast \
    -p com.yancotv.android \
    -a com.yancotv.android.debug.WATCHDOG_SMOKE_TEST
sleep 4
adb -s 192.168.68.66:5555 logcat -d -t 200 | grep -iE 'YancoWatchdogSmoke|ExoPlayerImpl: Init|FfmpegAudioRenderer|FfmpegVideoRenderer'
```

**The `-p com.yancotv.android` flag is required** — Android 8+ blocks
broadcasts to receivers in stopped packages without explicit package
targeting.

**Success looks like:**

```
YancoWatchdogSmoke: Forcing watchdog rebuild for verification…
ExoPlayerImpl: Init <new id> [AndroidXMedia3/1.5.1] [...]
YancoWatchdogSmoke: Watchdog rebuild dispatched — check ExoPlayerImpl: Init log line
```

The new `ExoPlayerImpl: Init` line has a different instance id than the one
from app cold-start — that's the rebuilt player. Crucially, **no
`Loaded FfmpegAudioRenderer` / `Loaded FfmpegVideoRenderer` lines should
follow it** — the rebuilt player uses `EXTENSION_RENDERER_MODE_OFF` by
design, so the FFmpeg extension is intentionally not loaded on the
replacement instance.

**On screen** (if a stream was playing when triggered): a brief buffer,
then playback resumes at the captured position. LIVE streams re-sync to
the live edge.

---

## Sentry (Stage 1.3 / MK.19.5)

Fires two synthetic events: a message-level event and an exception event.
Confirms the SDK is initialized, the network path to `sentry.io` works, and
events reach the project dashboard.

```bash
adb -s 192.168.68.66:5555 logcat -c
adb -s 192.168.68.66:5555 shell am broadcast \
    -p com.yancotv.android \
    -a com.yancotv.android.debug.SENTRY_SMOKE_TEST
sleep 10
adb -s 192.168.68.66:5555 logcat -d -t 500 | grep -iE 'YancoSentrySmoke|Sentry'
```

**Success looks like:**

```
YancoSentrySmoke: Firing Sentry smoke test events…
Sentry: sentry-external-modules.txt file was not found.    (benign — gradle plugin not used)
YancoSentrySmoke: Sentry smoke test events queued — check dashboard
```

**Then check the Sentry dashboard:**
[catbyte.sentry.io/projects/yancotv-androidtv](https://catbyte.sentry.io/projects/yancotv-androidtv/)
within ~30 seconds. You should see two new events:

1. `YancoTV Sentry smoke test (message) — version <X.Y.Z>+<code>` (INFO level)
2. `SentrySmokeTestException: YancoTV Sentry smoke test (exception) — captured stack trace, no real crash`

To filter the smoke-test noise out of normal Issues triage, search by
`error.type:SentrySmokeTestException`.

If no events appear after 30s and the logcat output looked correct, the
network egress is the problem — check the Fire TV's connectivity and
whether sentry.io resolves from that LAN.

---

## Stage 1.2 hands-on regression (MB-14)

Not adb-triggerable — needs a real broken stream. Closes MB-14 and ticks
Stage 1.2 once you confirm.

1. Open the app on the Fire TV.
2. Browse to ~10 channels you remember being audio-only (black screen, sound
   only). If you don't remember specifics, channels with non-English audio
   tracks (Arabic, Chinese networks; sports channels with surround sound)
   are common AC3/EAC3 carriers.
3. For each, let it play for a few seconds — confirm picture renders.
4. Note any channels that still come up audio-only OR crash.
5. Spot-check a few known-working channels to confirm no regression on the
   normal path.

Report results back as a count: `N of 10 recover, M still audio-only, K
crash`. That data closes the bug entry in `bugs.md`.

---

## R8 release-build verification (Stage 1.4)

R8 minification + resource shrinking are on for release builds (`isMinifyEnabled
= true`, `isShrinkResources = true` in `app/build.gradle.kts`). The keep rules
in `app/proguard-rules.pro` cover the reflection paths R8 would otherwise
break: FFmpeg JNI bridge, Kotlinx Serialization synthetic `$$serializer`
companions, Sentry reflective lookups, and SQLDelight runtime adapters.

```bash
cd packages/android
./gradlew :app:assembleRelease
adb -s 192.168.68.66:5555 install -r app/build/outputs/apk/release/app-release.apk
adb -s 192.168.68.66:5555 logcat -c
adb -s 192.168.68.66:5555 shell am start -n com.yancotv.android/.MainActivity
sleep 10
adb -s 192.168.68.66:5555 logcat -d -t 800 | grep -iE 'ExoPlayerImpl|Ffmpeg|Sentry|FATAL'
```

**Success looks like:**

```
YancoSentry: Sentry initialised — env=release
ExoPlayerImpl: Init [AndroidXMedia3/1.5.1] [duckie, AFTDCT31, Amazon, 28]
DefaultRenderersFactory: Loaded FfmpegAudioRenderer.
```

`env=release` (not `debug`) confirms `BuildConfig.DEBUG` survived minification.
The `Loaded FfmpegAudioRenderer` line confirms the reflection-loaded extension
was kept by the rules in `proguard-rules.pro`.

**APK size baseline:** ~15 MB release (down from ~29 MB debug). The 14 MB
delta is debug-only artifacts: LeakCanary integration, Compose tooling,
debug receivers (smoke-test broadcast handlers), full unstripped sources.

**The debug-only smoke-test receivers (`SentrySmokeTestReceiver`,
`WatchdogSmokeTestReceiver`) are NOT present in release** — they live in
`app/src/debug/` which AGP excludes from release variants. To exercise
Sentry on a release build, trigger a real error path (e.g., an unsupported
stream that fires `onPlayerError`).

## What this verification does NOT cover

- **Native libffmpegJNI segfaults.** Sentry's NDK signal handler is armed
  but can't be triggered on demand. Wait for one organically; if Sentry's
  dashboard shows a `libffmpegJNI` frame in the stack, the NDK integration
  is working.
- **Native FFmpeg crashes.** Segfaults inside `libffmpegJNI` are caught by
  Sentry's NDK signal handler but not by the Java watchdog. Difficult to
  trigger on demand. Wait for one to happen organically; if Sentry's
  dashboard shows a native-crash event with a `libffmpegJNI` frame in the
  stack, the NDK integration is working.
- **Background-rebuild signal delivery.** The watchdog smoke test runs
  while the activity is in `STARTED`. If a real FFmpeg crash fires while
  PlayerActivity is in `STOPPED` and MainActivity is hosting the mini-
  preview, the re-bind is exercised on `STARTED` re-entry instead of
  immediately. Same code path, different lifecycle window.
