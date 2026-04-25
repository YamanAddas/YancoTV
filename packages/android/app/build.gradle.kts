import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
}

// Sentry DSN — read from local.properties so the value stays per-machine and
// out of git history. Empty string when missing means Sentry init is a silent
// no-op (clean checkout / fresh dev box doesn't crash on launch).
val sentryDsn: String =
    rootProject.file("local.properties").let { propsFile ->
        if (propsFile.exists()) {
            Properties().apply { propsFile.inputStream().use { load(it) } }
                .getProperty("sentry.dsn", "")
        } else {
            ""
        }
    }

// ktlint applied per-module (the version-catalog `libs` accessor isn't
// available inside root `subprojects {}` blocks in Kotlin DSL, so each
// module wires it directly). ignoreFailures while we burn down style
// debt — flip to false in a later D.x once ktlintCheck is empty.
ktlint {
    version.set(libs.versions.ktlintCli.get())
    android.set(true)
    ignoreFailures.set(true)
    filter {
        // Don't lint generated code (Compose, KSP, R-class, etc.) —
        // generators don't follow human style and we don't want those
        // diffs in our reports.
        exclude { it.file.toString().contains("/build/generated/") }
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
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Real signing config arrives in MK.12. For MK.0 we reuse debug
            // so `assembleRelease` produces an installable APK.
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

    // Coil 3 images
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // WorkManager — background sync for sources (MK.3.3)
    implementation(libs.androidx.work.runtime.ktx)

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
