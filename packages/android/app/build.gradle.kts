import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.sentry)
}

// Sentry DSN — read from local.properties so the value stays per-machine and
// out of git history. Empty string when missing means Sentry init is a silent
// no-op (clean checkout / fresh dev box doesn't crash on launch).
val sentryProps: Properties =
    rootProject.file("local.properties").let { propsFile ->
        Properties().apply {
            if (propsFile.exists()) {
                propsFile.inputStream().use { load(it) }
            }
        }
    }
val sentryDsn: String = sentryProps.getProperty("sentry.dsn", "")
// Sentry auth token — used by the Sentry Gradle plugin at build time to
// upload R8 mapping files. Never embedded in the APK. Empty when missing
// means the plugin's upload step is skipped (a clean checkout still
// builds; just no symbolicated stack traces in the dashboard for that
// build's release crashes).
val sentryAuthToken: String = sentryProps.getProperty("sentry.auth.token", "")

// Stage 5.2.2 — sideload auto-update endpoint URL. Read from
// local.properties so per-machine / per-fork values stay out of git.
// Empty = updates disabled at runtime (UpdateChecker short-circuits
// when endpointUrl.isBlank()), so a clean checkout still builds + runs
// without configuring this. Production users wanting auto-updates set
//   update.endpoint=https://example.com/yancotv/update.json
// in local.properties.
val updateEndpoint: String = sentryProps.getProperty("update.endpoint", "").trim()

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
        versionCode = 1
        versionName = "0.1.0-mk0"

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
            // Real signing config arrives in Stage 5.7 (distribution
            // pipeline). Until then `assembleRelease` reuses debug signing
            // so the resulting APK is installable on test hardware.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.androidx.appcompat)

    // TV
    implementation(libs.tv.material)
    // MK.10.1 — Android TV launcher Recommendations channel.
    implementation(libs.tvprovider)

    // Media3 — powers PlayerActivity
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)

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
