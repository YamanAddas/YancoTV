import java.io.File
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.sentry)
}

// ─── Per-machine config ─────────────────────────────────────────────────
//
// Two-tier resolution for every secret a developer / CI runner has to
// supply at build time:
//
//   1. **Environment variable** (preferred). Keeps secrets OFF disk in
//      the project tree so filesystem-walking scanners (TruffleHog,
//      Gitleaks, yancoxplorer Mobile Launch Readiness) can't find them
//      when they scan the working directory. Set in your shell profile
//      (`~/.bashrc`, `~/.zshrc`, `setx` on Windows, or Android Studio's
//      Run → Edit Configurations → Environment variables).
//
//   2. **`local.properties`** (fallback). The legacy on-disk pattern.
//      File is gitignored so the values never reach git history, but
//      they DO sit on disk where any process with read access to the
//      project dir can see them. After rotating a credential, prefer
//      moving its new value to an env var rather than back into this
//      file — `MB-203` audit thread / `docs/security/AUDIT_NOTES.md`
//      for the rationale.
//
// Both empty = the feature gates off cleanly (Sentry no-op, update
// checker no-op, debug signing on release variant). A fresh clone
// still builds + installs without any setup.
val sentryProps: Properties =
    rootProject.file("local.properties").let { propsFile ->
        Properties().apply {
            if (propsFile.exists()) {
                propsFile.inputStream().use { load(it) }
            }
        }
    }

/**
 * Resolve a secret with env-var precedence over `local.properties`.
 * Empty string when neither source has a non-blank value.
 */
fun resolveSecret(envVar: String, propsKey: String): String = System.getenv(envVar)?.takeIf { it.isNotBlank() }
    ?: sentryProps.getProperty(propsKey, "")

// Sentry DSN — read by SentryInit.kt at app launch via
// BuildConfig.SENTRY_DSN. Empty means "Sentry off" (init no-op).
val sentryDsn: String = resolveSecret("YANCOTV_SENTRY_DSN", "sentry.dsn")

// Sentry auth token — used by the Sentry Gradle plugin at build time to
// upload R8 mapping files. Never embedded in the APK. Empty when missing
// means the plugin's upload step is skipped (a clean checkout still
// builds; just no symbolicated stack traces in the dashboard for that
// build's release crashes). The env-var name `SENTRY_AUTH_TOKEN` is the
// Sentry CLI / plugin's own convention so the same export works with
// other Sentry tooling.
val sentryAuthToken: String = resolveSecret("SENTRY_AUTH_TOKEN", "sentry.auth.token")

// Stage 5.2.2 — sideload auto-update endpoint URL. Read from
// local.properties so per-machine / per-fork values stay out of git.
// Empty = updates disabled at runtime (UpdateChecker short-circuits
// when endpointUrl.isBlank()), so a clean checkout still builds + runs
// without configuring this. Production users wanting auto-updates set
//   update.endpoint=https://example.com/yancotv/update.json
// in local.properties.
val updateEndpoint: String = sentryProps.getProperty("update.endpoint", "").trim()

// MB-201 / Stage 5.7 — release signing keystore.
//
// Read from local.properties so the keystore password / alias / path
// stay per-machine (the file is gitignored). When any of the four
// values is missing the release config silently falls back to debug
// signing — a fresh clone still builds + installs to test hardware
// without setting up the keystore. Once we cut a real distribution
// build (Play / Amazon / GitHub Releases), local.properties on the
// release machine MUST have all four set; otherwise the resulting
// APK is debug-signed and rejected by every store.
//
// Path is resolved relative to packages/android/, matching how the
// keystore was generated.
val releaseKeystorePath: String = sentryProps.getProperty("release.keystore.path", "").trim()
val releaseKeystorePassword: String = sentryProps.getProperty("release.keystore.password", "")
val releaseKeyAlias: String = sentryProps.getProperty("release.key.alias", "").trim()
val releaseKeyPassword: String = sentryProps.getProperty("release.key.password", "")
val releaseKeystoreFile: java.io.File? =
    if (releaseKeystorePath.isNotBlank()) {
        rootProject.file(releaseKeystorePath)
    } else {
        null
    }
val releaseSigningConfigured: Boolean =
    releaseKeystoreFile?.exists() == true &&
        releaseKeystorePassword.isNotBlank() &&
        releaseKeyAlias.isNotBlank() &&
        releaseKeyPassword.isNotBlank()

// ktlint applied per-module (the version-catalog `libs` accessor isn't
// available inside root `subprojects {}` blocks in Kotlin DSL, so each
// module wires it directly).
//
// MB-202 (2026-04-28) — flipped `ignoreFailures = false`. The
// burn-down landed: ran ktlintFormat + manual fixups (5 line-length
// breaks for description copy that wouldn't auto-wrap). Repo-level
// `.editorconfig` disables `function-naming` (Compose convention),
// `backing-property-naming` (StateFlow `_foo`/`foo`), and `filename`
// (multi-decl Kotlin files we keep) — see the comments there for
// rationale.
ktlint {
    version.set(libs.versions.ktlintCli.get())
    android.set(true)
    ignoreFailures.set(false)
    filter {
        // Don't lint generated code (Compose, KSP, R-class, etc.) —
        // generators don't follow human style and we don't want those
        // diffs in our reports.
        //
        // Path normalisation is mandatory: `it.file.toString()` on Windows
        // produces backslashes (`D:\...\build\generated\...`), which
        // `contains("/build/generated/")` misses. Replace separators
        // before the substring check so the filter matches on both OSes.
        exclude {
            val normalized = it.file.path.replace('\\', '/')
            normalized.contains("/build/generated/")
        }
    }
}

android {
    namespace = "com.yancotv.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yancotv.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 31
        versionName = "1.6.7"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        // Stage 1.3 / MK.19.5 — Sentry DSN baked into BuildConfig at compile
        // time. Source: local.properties → sentry.dsn (gitignored, per-machine).
        // The constant is read by YancoApp.onCreate; empty means "Sentry off
        // for this build" (init becomes a no-op).
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        // Stage 5.2.2 — read by AppModules' UpdateChecker singleton.
        // Empty = updates disabled (the checker short-circuits before
        // any HTTP call).
        buildConfigField("String", "UPDATE_ENDPOINT", "\"$updateEndpoint\"")
        // Audit catch — AGENTS.md threat-model section promises forks
        // can override via the `OPENSUBTITLES_API_KEY` env var, but the
        // pre-fix build only read local.properties. Layered fallback
        // now: env var → local.properties → empty string. Matches the
        // documented contract so a fork that follows the AGENTS guide
        // doesn't get an empty Api-Key header + silent 401s.
        val opensubtitlesApiKey =
            System.getenv("OPENSUBTITLES_API_KEY")
                ?: sentryProps.getProperty("opensubtitles.apiKey", "")
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$opensubtitlesApiKey\"")
    }

    // MB-201 — release signing config (only registered when local.properties
    // has all four release.* values AND the keystore file exists). When any
    // is missing the release buildType falls through to debug signing below.
    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Modern PKCS12 keystore — generated by keytool with
                // `-storetype PKCS12`. v2 + v3 signing schemes both on
                // for max install-target compatibility (v2 = N+, v3 =
                // P+, v1 stays on as a safety net for older OEM ROMs
                // that misread v2-only APKs).
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Stage 1.4 — R8 baseline. Code shrinking (+ obfuscation) +
            // resource shrinking on for release. Keep rules in
            // proguard-rules.pro; consumer rules ship inside dependent AARs
            // (Compose, Sentry, Media3, OkHttp, Ktor, Coil, etc.).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // MB-201 — pick the release keystore when local.properties has
            // it configured, else fall back to debug signing so a fresh
            // checkout still builds. Distribution builds MUST sign with
            // release; verify with the gradle log line below before
            // uploading anywhere.
            // MB-349 — assign a config unconditionally here and enforce the
            // MB-295 rule from the task graph instead (see the guard below the
            // `android { }` block). Nothing in this expression may throw: this
            // block is evaluated when Gradle CONFIGURES :app, which happens for
            // EVERY task in the build, so a throw here takes down ktlint, the
            // unit tests and assembleDebug too — none of which sign anything.
            signingConfig =
                if (releaseSigningConfigured) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates StrictMode + future debug-only diagnostics
        // in YancoApp. Off by default in AGP 8 — has to be opted in.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/DEPENDENCIES",
                )
        }
    }

    testOptions {
        unitTests {
            // Lets unit tests construct framework-touching classes
            // (e.g. androidx.media3.common.PlaybackException, which calls
            // SystemClock.elapsedRealtime() in its constructor) without
            // standing up Robolectric. Un-mocked methods return 0 / null /
            // false instead of throwing — fine for tests that only care
            // about the wrapping object's logic, not Android framework
            // behaviour. MK.9.4 FFmpeg-classifier test depends on this.
            isReturnDefaultValues = true
        }
    }

    lint {
        // Launcher + banner assets are the MK.12 pass; the manifest
        // already documents the placeholder state. Keeping this as a
        // blocking lint error would fail CI on every run until then.
        disable += "MissingTvBanner"
        // MB-339 — the fr/es `many` category, demoted to informational rather
        // than baselined or fixed. Three reasons, in order:
        //
        //  1. Modern CLDR gives French and Spanish a `many` category, but it
        //     selects only for EXACT multiples of a million ("1 000 000 **de**
        //     chaînes"). Absent it, Android falls back to `other`, which is a
        //     numeral-led count and perfectly comprehensible.
        //  2. For most of the 22 plurals the quantity is unreachable — a source
        //     expiring in 1,000,000 days, a million hidden channels. Authoring
        //     grammar for states that cannot occur is worse than the fallback.
        //  3. Duplicating the `other` text into a `many` item would silence the
        //     check without changing a single rendered string, which is the
        //     cargo-cult version of fixing it.
        //
        // The part that DOES matter — Arabic declaring all six categories, no
        // plural missing `other`, no dead categories, no format-argument
        // overrun — is asserted by PluralResourceParityTest, which is locale-
        // aware and fails on a seeded defect. `informational` rather than
        // `disable` so the finding stays visible in the report.
        informational += "MissingQuantity"
        // D.1a baseline (2026-04-24): captures the 113 warnings that
        // existed at commit time so NEW warnings fail the build but
        // old ones don't block. To regenerate after intentional cleanup:
        //   ./gradlew :app:updateLintBaseline
        // Categories in the baseline today (top 5):
        //   57 GradleDependency  — out-of-date deps, MK.12 bump
        //   8  DefaultLocale     — toLowerCase() / format() w/o Locale
        //   7  HardcodedText     — UI strings, MK.10 i18n pass
        //   6  AndroidGradlePluginVersion — AGP bump is its own decision
        //   5  IconLauncherShape — MK.12 launcher work
        baseline = file("lint-baseline.xml")
    }
}

// Compose compiler stability + recomposition reports. Off by default —
// every build would otherwise litter `build/compose_compiler/` with
// markdown/csv. Enable with:
//   ./gradlew :app:assembleDebug -PcomposeCompilerReports=true -PcomposeCompilerMetrics=true
// Then read app/build/compose_compiler/*-classes.txt for "unstable" hits
// and *-composables.txt for "restartable but not skippable" composables.
// First red flag is anything in ui/shell/* showing as unstable.
composeCompiler {
    if (project.findProperty("composeCompilerReports") == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
    }
    if (project.findProperty("composeCompilerMetrics") == "true") {
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

// ─── MB-349 — MB-295's release-signing guard, moved off the configuration path
//
// MB-295 was right about the RULE: a release APK signed with the debug key
// installs fine locally and can never be updated by anyone holding a real
// build, so producing one silently is a one-way door for the whole install
// base. It was wrong about WHERE to enforce it. The check lived as a `throw`
// inside `buildTypes { release { … } }`, and Gradle evaluates that block when
// it CONFIGURES this project — which it does for every task in the build.
//
// So on any machine without a release keystore, i.e. every CI runner, nothing
// could run at all: ktlint, the SQLDelight migration check, both unit-test
// suites, assembleDebug, and even the macOS iOS-compile job all died during
// configuration, before doing a single second of real work. CI went red on
// 2026-07-25 with `faa9244` and stayed red. The workflow's
// `-PallowUnsignedRelease=true` escape hatch was passed only to the
// assembleRelease step — the one place it was not sufficient — because the
// failure was never about that task.
//
// `whenReady` fires after configuration and before execution, which is the
// earliest point where "is a task that SIGNS something actually going to run?"
// can be answered. Signing tasks are matched by exact name rather than by a
// "contains Release" pattern: `lintRelease`, `compileReleaseKotlin` and
// `packageReleaseResources` all carry Release in the name and none of them
// produce a distributable, so matching loosely would just reintroduce the bug
// one level down. `releasePackage` and `bundleRelease` are covered
// transitively — both depend on the packaging tasks named here.
val releaseSigningTasks = setOf("packageRelease", "packageReleaseBundle", "validateSigningRelease")

gradle.taskGraph.whenReady {
    if (releaseSigningConfigured) return@whenReady
    val signingInGraph = allTasks.any { it.project.path == project.path && it.name in releaseSigningTasks }
    if (!signingInGraph) return@whenReady
    if (project.hasProperty("allowUnsignedRelease")) {
        // Explicit opt-in, used by CI (see .github/workflows/android-tests.yml).
        // The point of the release build there is to exercise R8 / resource
        // shrinking / ProGuard rules, which the debug build never touches —
        // signing is irrelevant to that and CI holds no keystore.
        logger.warn(
            "allowUnsignedRelease set — producing a DEBUG-SIGNED release APK. " +
                "Valid for R8/lint validation only. This artifact CANNOT be " +
                "distributed: Android refuses same-package installs across " +
                "signing keys, so shipping it would strand every existing user.",
        )
        return@whenReady
    }
    throw GradleException(
        "Release signing is not configured. Set release.keystore.path, " +
            "release.keystore.password, release.key.alias and " +
            "release.key.password in local.properties before cutting a " +
            "distribution build.\n" +
            "If you only need to validate R8 (CI, local minify check), " +
            "re-run with -PallowUnsignedRelease=true — that produces a " +
            "debug-signed APK which must never be distributed.",
    )
}

dependencies {
    implementation(project(":shared"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)

    // TV
    implementation(libs.tv.material)
    // MK.10.1 — Android TV launcher Recommendations channel.
    implementation(libs.tvprovider)

    // MK.26 Track B — Google Cast (CAF). Gated behind Google Play Services so
    // it's dark on Fire OS. Isolated from Media3 (the Chromecast is its own
    // remote player), so no media3 bump and no risk to the local ExoPlayer.
    implementation(libs.play.services.cast.framework)
    implementation(libs.mediarouter)

    // MK.26 B.2 — ffmpeg-kit (16KB-page, TLS-enabled fork) for the on-device
    // cast proxy: TS->HLS remux + AC-3->AAC transcode so raw-TS IPTV plays on a
    // bare Chromecast's Default Receiver. JamaisMagic -tls build (--enable-gnutls)
    // replaces com.moizhassan's TLS-less build, which failed "https protocol not
    // found" on every https provider URL. Same com.arthenica.ffmpegkit package.
    // Bundles its own libffmpegkit.so + libav*.so; the vendored media3 FFmpeg
    // audio decoder is statically linked into libffmpegJNI.so (different name),
    // so no .so collision — verify at packaging.
    implementation(libs.ffmpeg.kit)

    // Media3 — powers PlayerActivity
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)

    // MK.26.A.1 — embedded Ktor (CIO) server for the LAN companion-handoff
    // receiver. Android-only (the receiver runs on the TV). The matching
    // ktor client + kotlinx-serialization live in :shared as `implementation`
    // (not on this module's compile classpath), so the JSON converter is
    // declared here explicitly for ContentNegotiation.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // MK.26.A.3 — ktor client (OkHttp engine) for the SENDER side. A
    // dedicated client WITHOUT the cleartext allow-list interceptor (the
    // target is the user's own TV on the LAN, not a provider host).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    // MK.9 — checker-qual annotations are referenced by the vendored
    // androidx.media3.decoder.ffmpeg sources for nullness static analysis.
    // compileOnly — annotations have CLASS retention at most, never needed
    // at runtime, so they don't need to land in the APK.
    compileOnly(libs.checker.qual)

    // Stage 1.3 / MK.19.5 — Sentry crash + error reporting. Meta-package
    // pulls in core + ANR detection + lifecycle hooks + NDK crash capture.
    // NDK matters: the FFmpeg renderer's native crashes segfault inside
    // libffmpegJNI and never reach Java's UncaughtExceptionHandler — Sentry's
    // signal handler catches those and uploads on next launch.
    implementation(libs.sentry.android)

    // Kermit (Stage 1.3) — used by SentryKermitWriter to bridge log output
    // to Sentry breadcrumbs/events. Already a transitive of :shared but as
    // an `implementation` dep there it's not on the app module's compile
    // classpath; declaring it here avoids depending on a leak.
    implementation(libs.kermit)

    // kotlinx.io (Stage 3.1 / MK.14.1e) — RecordingFileOutput wraps a
    // FileOutputStream as a Sink for the shared-side recorders. Same
    // "transitive of :shared but not on app compile classpath" reason.
    implementation(libs.kotlinx.io.core)

    // Coil 3 images
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // WorkManager — background sync for sources (MK.3.3)
    implementation(libs.androidx.work.runtime.ktx)

    // DocumentFile — Stage 3.1 / MK.14.2-storage. Recording engine uses
    // it to write into a user-picked SAF folder when one's configured.
    implementation(libs.androidx.documentfile)

    // LeakCanary — debug-only. Installs its own ContentProvider, watches
    // every Activity/Fragment/ViewModel for retained references after
    // destroy, dumps a heap when one survives. Pure additive; release
    // APK is untouched. Triage tip: ExoPlayer + Coil generate noisy false
    // positives during type-switches — use IgnoredReferences if needed.
    debugImplementation(libs.leakcanary.android)

    // ───── test ─────
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Stage 5.2.3 — emit `update.json` for the sideload auto-update channel.
//
// The shape matches what `com.yancotv.shared.update.UpdateChecker` parses
// (versionCode + versionName + downloadUrl, with optional releaseNotes /
// minOsApi). Wired as a `finalizedBy` on `assembleRelease` so cutting a
// release also drops the JSON next to the APK at
// `app/build/outputs/update.json` — upload BOTH artifacts (or pin the JSON
// to a gist / Pages site whose URL goes into `update.endpoint`) and the
// running app's "Check now" picks up the new version.
//
// For testing the install flow on a dev box without first cutting a real
// release: run with `-Pupdate.testBump=true` to emit a JSON whose
// versionCode is the current build + 1. The running app then sees an
// "update available", lets you exercise download → install, and lands
// you on the same versionCode — no real bump in source needed.
//
// Properties (all optional):
//   -Pupdate.testBump=true               bump emitted versionCode by 1
//   -Pupdate.downloadUrl=https://...     override the APK URL (highest precedence)
//   -Pupdate.releaseNotes="What's new…"  free-text notes
// Or in `local.properties`:
//   update.download.url=https://...      same as -Pupdate.downloadUrl, lower precedence
val generateUpdateJson by tasks.registering {
    group = "yancotv"
    description = "Emit app/build/outputs/update.json for the sideload auto-update channel."

    val outputFile = layout.buildDirectory.file("outputs/update.json")
    outputs.file(outputFile)
    // Re-run when any input changes — so a bump-only edit (no source diff)
    // still rewrites the JSON.
    inputs.property("versionCode", android.defaultConfig.versionCode ?: 1)
    inputs.property("versionName", android.defaultConfig.versionName ?: "?")
    inputs.property(
        "testBump",
        project.findProperty("update.testBump")?.toString() ?: "false",
    )
    inputs.property(
        "downloadUrlOverride",
        (project.findProperty("update.downloadUrl") as? String)
            ?: sentryProps.getProperty("update.download.url", ""),
    )
    inputs.property(
        "releaseNotes",
        (project.findProperty("update.releaseNotes") as? String) ?: "",
    )

    doLast {
        val testBump = project.findProperty("update.testBump")?.toString() == "true"
        val baseVersionCode = android.defaultConfig.versionCode ?: 1
        val baseVersionName = android.defaultConfig.versionName ?: "?"
        val emittedVersionCode = if (testBump) baseVersionCode + 1 else baseVersionCode
        val emittedVersionName =
            if (testBump) "$baseVersionName-test" else baseVersionName
        val downloadUrl =
            (project.findProperty("update.downloadUrl") as? String)
                ?: sentryProps
                    .getProperty(
                        "update.download.url",
                        "https://example.invalid/yancotv-$emittedVersionCode.apk",
                    )
        val releaseNotes =
            (project.findProperty("update.releaseNotes") as? String)
                ?.takeIf { it.isNotBlank() }

        val payload =
            linkedMapOf<String, Any?>(
                "versionCode" to emittedVersionCode,
                "versionName" to emittedVersionName,
                "downloadUrl" to downloadUrl,
            )
        if (releaseNotes != null) payload["releaseNotes"] = releaseNotes

        // MB-369 — SHA-256 of the release APK, when it exists, so the
        // in-app updater can verify the download before invoking the
        // installer. Omitted (never faked) when:
        //  - the APK has not been built in this invocation (standalone
        //    generateUpdateJson run) — hashing a stale file would pin the
        //    WRONG digest to the new version and brick the update;
        //  - update.testBump is set — that mode advertises versionCode+1
        //    while pointing at the CURRENT build, so a digest would only
        //    ever mismatch. Absent sha = "no digest check", the documented
        //    pre-MB-369 behaviour the fleet already relies on.
        val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (!testBump && releaseApk.exists()) {
            val md = MessageDigest.getInstance("SHA-256")
            releaseApk.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    md.update(buf, 0, n)
                }
            }
            payload["sha256"] = md.digest().joinToString("") { b -> "%02x".format(b) }
        } else if (!testBump) {
            logger.lifecycle("generateUpdateJson: no release APK on disk — update.json emitted WITHOUT sha256")
        }

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            groovy.json.JsonOutput
                .prettyPrint(groovy.json.JsonOutput.toJson(payload)),
        )

        logger.lifecycle("[generateUpdateJson] wrote $target")
        logger.lifecycle("  versionCode = $emittedVersionCode")
        logger.lifecycle("  versionName = $emittedVersionName")
        logger.lifecycle("  downloadUrl = $downloadUrl")
        if (testBump) {
            logger.lifecycle(
                "  (testBump=true: emitted versionCode is build+1 so the running app sees an update)",
            )
        }
    }
}

// Cutting a release also drops the matching JSON. Debug intentionally
// not wired — `assembleDebug` happens dozens of times a session and the
// JSON would just be stale. Run `:app:generateUpdateJson` directly when
// you want it for a debug build.
afterEvaluate {
    tasks.findByName("assembleRelease")?.finalizedBy(generateUpdateJson)
}

// Stage 5.7 — copy every artifact a real release needs into one
// folder ready to upload. Runs after both `assembleRelease`
// (signed APK + update.json finalizer) and `bundleRelease` (signed
// AAB for Play). Output:
//
//   app/build/outputs/release-package/
//     yancotv-<versionName>-<versionCode>.apk    ← Amazon, GitHub Releases, sideload
//     yancotv-<versionName>-<versionCode>.aab    ← Play Console
//     update.json                                ← endpoint payload (see Stage 5.2)
//     SHA256SUMS                                 ← integrity sums for users who manually verify
//
// The user just zips this folder (or uploads the contents directly to
// each store) — no more "where was that APK again" or "did I copy the
// right update.json?". Run with:
//
//   ./gradlew :app:releasePackage
//
// (which auto-runs assembleRelease + bundleRelease + generateUpdateJson
// because of `dependsOn`).
// MB-364 — a distributable build with no crash reporting must be a
// DELIBERATE choice, never a silent default. `sentryDsn` resolves from the
// YANCOTV_SENTRY_DSN env var or local.properties `sentry.dsn`; when both are
// absent the app builds fine and SentryInit silently no-ops — right for a
// clean-checkout debug build, wrong for something you hand to users. This
// mirrors the release-signing guard above: `releasePackage` (the only task
// whose output is meant to be distributed) fails on a blank DSN unless
// `-PallowNoCrashReporting=true` is passed explicitly. `assembleRelease`
// alone stays permissive on purpose — CI runs it for R8 validation with no
// secrets and no intent to distribute.
val requireCrashReporting by tasks.registering {
    group = "yancotv"
    description = "Fails when the release has no Sentry DSN, unless -PallowNoCrashReporting=true."
    doFirst {
        if (sentryDsn.isBlank() && !project.hasProperty("allowNoCrashReporting")) {
            throw GradleException(
                "Sentry DSN is empty — this build would report NO crashes, silently. " +
                    "Set YANCOTV_SENTRY_DSN (env) or sentry.dsn in local.properties, " +
                    "or pass -PallowNoCrashReporting=true to ship without crash reporting on purpose.",
            )
        }
        if (sentryAuthToken.isBlank() && !project.hasProperty("allowNoCrashReporting")) {
            logger.warn(
                "SENTRY_AUTH_TOKEN is empty — the R8 mapping will NOT be uploaded, so any " +
                    "crash from this build will be obfuscated in the dashboard. The DSN is set, " +
                    "so this is a warning rather than a failure.",
            )
        }
    }
}

val releasePackage by tasks.registering {
    group = "yancotv"
    description = "Bundle signed APK + AAB + update.json + SHA256SUMS for distribution."

    // Resolve at config time so dependsOn / inputs / outputs are wired.
    val apkFileProvider = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val aabFileProvider =
        layout.buildDirectory.file(
            "outputs/bundle/release/app-release.aab",
        )
    val updateJsonProvider = layout.buildDirectory.file("outputs/update.json")
    val outDirProvider = layout.buildDirectory.dir("outputs/release-package")

    inputs.file(apkFileProvider)
    inputs.file(aabFileProvider)
    inputs.file(updateJsonProvider)
    outputs.dir(outDirProvider)

    dependsOn(requireCrashReporting, "assembleRelease", "bundleRelease", generateUpdateJson)

    doLast {
        val versionName = android.defaultConfig.versionName ?: "unknown"
        val versionCode = android.defaultConfig.versionCode ?: 0
        val tag = "yancotv-$versionName-$versionCode"

        val outDir = outDirProvider.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val apk = apkFileProvider.get().asFile
        val aab = aabFileProvider.get().asFile
        val json = updateJsonProvider.get().asFile

        val apkOut = File(outDir, "$tag.apk")
        val aabOut = File(outDir, "$tag.aab")
        val jsonOut = File(outDir, "update.json")

        apk.copyTo(apkOut, overwrite = true)
        aab.copyTo(aabOut, overwrite = true)
        json.copyTo(jsonOut, overwrite = true)

        // MB-364 follow-up — archive the R8 mapping beside the artifacts it
        // deobfuscates. The Sentry plugin uploads it only when an auth token
        // is configured; this copy is the fallback that makes a crash from
        // THIS build readable even when that upload never ran. Named by the
        // same tag as the binaries so the pairing is unambiguous months
        // later, and kept OUT of SHA256SUMS (it is for the maintainer, not
        // a user-verifiable artifact).
        val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
        if (mapping.exists()) {
            mapping.copyTo(File(outDir, "$tag-mapping.txt"), overwrite = true)
        } else {
            logger.warn("releasePackage: no mapping.txt found — R8 mapping NOT archived for $tag")
        }

        // SHA256 sums for the two binaries — gives users (and us) a way
        // to verify the file they're holding is the one we shipped, in
        // case GitHub Releases / a CDN ever serves a corrupted blob.
        val md = MessageDigest.getInstance("SHA-256")
        fun sha256Of(f: File): String {
            md.reset()
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        val sums = buildString {
            appendLine("${sha256Of(apkOut)}  ${apkOut.name}")
            appendLine("${sha256Of(aabOut)}  ${aabOut.name}")
        }
        File(outDir, "SHA256SUMS").writeText(sums)

        logger.lifecycle("[releasePackage] wrote ${outDir.absolutePath}")
        outDir.listFiles()?.sortedBy { it.name }?.forEach {
            logger.lifecycle("  ${it.name}  (${it.length()} bytes)")
        }
        logger.lifecycle("[releasePackage] versionName=$versionName versionCode=$versionCode")
    }
}

// Stage 1.4 follow-up — Sentry Gradle plugin. Uploads the R8 mapping file
// to Sentry during release builds so obfuscated crash stack traces in the
// dashboard get symbolicated back to readable Kotlin/Java source.
//
// Auth token is read from local.properties (gitignored). Empty token =
// upload step is a no-op; the build still succeeds, just without
// symbolication for that build's release events.
//
// Native debug-symbol upload is OFF — the vendored libffmpegJNI.so files
// were stripped at build time (see docs/build/ffmpeg-extension.md), so
// there's no debug info to upload anyway. We accept obfuscated function
// names in the rare native-crash event in exchange for a smaller APK.
//
// Auto-installation of the Sentry SDK is OFF — we init manually in
// YancoApp.onCreate via SentryInit so the DSN can be sourced from
// local.properties. The plugin would otherwise re-add the SDK and bake
// the DSN into the manifest.
//
// Tracing instrumentation is OFF — Stage 1.3 explicitly chose
// crash + error reporting only; performance tracing has its own CPU and
// network cost we'd want to budget deliberately.
sentry {
    org.set("catbyte")
    projectName.set("yancotv-androidtv")
    authToken.set(sentryAuthToken)

    // Mapping upload — the actual point of this plugin for us.
    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(sentryAuthToken.isNotBlank())

    // Don't bundle source context (would add ~MB of source tree as build
    // metadata). Stack traces with file+line are enough.
    includeSourceContext.set(false)

    // Native symbol upload — see comment above; off because we strip.
    uploadNativeSymbols.set(false)

    // Disable the auto-install ContentProvider injection — we init in
    // YancoApp manually, see SentryInit.kt + AndroidManifest.xml's
    // io.sentry.auto-init=false meta-data.
    autoInstallation {
        enabled.set(false)
    }

    // Bytecode instrumentation for tracing — off, see comment above.
    tracingInstrumentation {
        enabled.set(false)
    }
}

// MB-339 — PluralResourceParityTest reads src/main/res/values*/strings.xml off
// the filesystem, because the resource files ARE the artefact under test and
// Robolectric resolves only one locale per run. Gradle has no way to know that,
// so without this declaration the test task stays UP-TO-DATE when a translation
// changes and silently never runs. That is not hypothetical: the first
// negative-control of that test reported GREEN against a deliberately broken
// Arabic plural, because the task was skipped rather than executed. Declaring
// the directory as an input makes an edit to any strings.xml re-run the suite.
tasks.withType<Test>().configureEach {
    inputs
        .dir(layout.projectDirectory.dir("src/main/res"))
        .withPropertyName("androidStringResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
