# Releasing YancoTV (native Android)

The distilled runbook. Everything here was learned the hard way; the "why"
notes are the scar tissue. Desktop releases are unrelated (see CHANGELOG.md).

## Prerequisites (once per machine)

- `JAVA_HOME` → Android Studio's JBR (`C:\Program Files\Android\Android Studio\jbr`).
  The scoop Temurin JRE cannot compile (`does not provide JAVA_COMPILER`).
- `packages/android/local.properties` (gitignored) must carry:
  - `release.keystore.path` / `release.keystore.password` / `release.key.alias` / `release.key.password`
  - `sentry.dsn` + `sentry.auth.token` (MB-364 — without the DSN,
    `releasePackage` refuses to build; without the token the R8 mapping is
    not uploaded and the build warns)
  - `update.download.url` / `update.endpoint`
- **The keystore file itself is UNBACKED-UP** — if it is lost, existing
  installs can never be updated again (signature mismatch). Back it up first.

## Cut a release

1. Bump `versionCode` + `versionName` in `packages/android/app/build.gradle.kts`.
2. Run the full gate (this is what CI runs, plus R8):

   ```
   ./gradlew :shared:ktlintCheck :app:ktlintCheck :shared:testDebugUnitTest \
             :app:testDebugUnitTest :app:assembleDebug :app:lintRelease \
             :app:assembleRelease -PallowUnsignedRelease=true
   ```

3. Smoke-test the R8 build ON HARDWARE without nuking device data:
   copy `app-release.apk`, re-sign it with the debug key
   (`apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android ...`),
   `adb install -r` over the debug install. You get minified, non-debuggable
   release code with the debug signature, so no uninstall and no data loss.
   Start the app, play a channel, open the guide, check
   `logcat` for `FATAL EXCEPTION`. R8 has broken this app at runtime before
   (MK.26: Ktor → java.lang.management) while compiling green.
4. Cut the package:

   ```
   ./gradlew :app:releasePackage -Pupdate.releaseNotes="..."
   ```

   Output in `app/build/outputs/release-package/`:
   `yancotv-<ver>-<code>.apk` + `.aab` + `update.json` + `SHA256SUMS` +
   `yancotv-<ver>-<code>-mapping.txt`.

   Guard behaviour (MB-364): fails on a blank Sentry DSN unless you pass
   `-PallowNoCrashReporting=true`. A blind release is a keystroke, not a default.

5. **Archive the `-mapping.txt` somewhere durable.** It is the only way to
   read a crash from this exact build if the Sentry upload didn't run.

## Publish

1. GitHub release on `YamanAddas/yancotv-releases`, tag `<ver>-<code>`
   (e.g. `1.5.4-22`). Upload the APK **renamed to `yancotv.apk`** — the
   in-app updater fetches `releases/latest/download/yancotv.apk`, so the
   asset name is load-bearing. Upload `SHA256SUMS` beside it.
2. **THEN update `update.json` on the Pages branch** of `yancotv-releases`
   with the new `versionCode` / `versionName` / notes.

   > **The release trap:** publishing the GitHub release alone does NOTHING
   > for existing installs. They poll the Pages `update.json`; until that
   > file is bumped, every device is told it is already current. 1.5.3 was
   > published and invisible to all 1.4.0 devices for exactly this reason.

3. Verify on a real device that shipped the PREVIOUS version:
   Settings → check for update → it must offer the new one, install it, and
   come back with data intact (release-signed over release-signed).

## After

- Tag the source repo (`git tag v<ver>-<code>` on the release commit) so the
  mapping, the APK and the code line up months later.
- Watch Sentry for new-release crashes for a day. If the dashboard shows
  obfuscated frames, the auth token wasn't set at build time — deobfuscate
  locally against the archived `-mapping.txt` (`retrace` / `proguard`).

## Device-testing notes that keep biting

- Debug and release builds share the package name. Installing a debug build
  over a user's release install (or vice versa) = signature mismatch =
  Android forces uninstall = **their entire catalogue and history is gone**.
  The Google TV memory entry exists because of this. The re-sign trick in
  step 3 is how you test release code without crossing that line.
- Every `installDebug` used to wipe the catalogue on next launch (MB-363,
  fixed). If Home is empty right after an install, wait out the sync or
  check `auto-sync skipped` lines before diagnosing anything.
