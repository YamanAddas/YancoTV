plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.ktlint)
}

// Same wiring as :app — see packages/android/app/build.gradle.kts for
// why we apply per-module instead of via root subprojects {}.
ktlint {
    version.set(libs.versions.ktlintCli.get())
    android.set(false) // KMP module — Android-specific rules don't apply
    // MB-202 — burn-down landed; flipped to false. See app/build.gradle.kts
    // for the .editorconfig disable list rationale.
    ignoreFailures.set(false)
    filter {
        // Match on both `/` (POSIX) and `\` (Windows) — `it.file.path` on
        // Windows uses backslashes, so a literal `/build/generated/` check
        // misses the SQLDelight-generated `YancoDb.kt` etc.
        exclude {
            val normalized = it.file.path.replace('\\', '/')
            normalized.contains("/build/generated/")
        }
    }
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.json.io)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.ext)
                implementation(libs.koin.core)
                implementation(libs.kermit)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
        // Symmetric with `iosMain` above. The default Kotlin hierarchy
        // template is not applied to this module (the explicit
        // `dependsOn` edges opt out of it), so the shared iOS test
        // intermediate has to be declared by hand too — otherwise
        // iOS-only test helpers have nowhere to live and the structure
        // silently diverges from the main source sets.
        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
            iosX64Test.dependsOn(this)
            iosArm64Test.dependsOn(this)
            iosSimulatorArm64Test.dependsOn(this)
        }
    }
}

android {
    namespace = "com.yancotv.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            // Same rationale as :app's testOptions — let unit tests touch
            // `android.util.Log` and similar framework methods without
            // standing up Robolectric. Stage 1.5 SourcesBackupTest depends
            // on this (SourcesBackup.restoreInto logs an info line).
            isReturnDefaultValues = true
        }
    }
}

sqldelight {
    databases {
        create("YancoDb") {
            packageName.set("com.yancotv.shared.db")
            // Stage 1.5 — `verifyMigrations` and `schemaOutputDirectory`
            // were tried but SQLDelight's GenerateSchemaTask depends on
            // sqlite-jdbc whose Windows native binding doesn't link cleanly
            // against the Android Studio JBR (NoSuchMethodError on
            // org.sqlite.core.NativeDB._open_utf8). Migration verification
            // is done via runtime tests in `androidUnitTest` instead — see
            // MigrationTest.kt. Those use JdbcSqliteDriver via Kotlin test
            // classpath which loads the native lib correctly.
        }
    }
}

// SQLDelight 2.0.2 auto-creates `verifyCommonMainYancoDbMigration` whenever
// there are `.sqm` migration files in `commonMain`, regardless of whether
// `verifyMigrations` is set in the DSL (the DSL property tunes test-time
// verification, not the build-time task). The task uses sqlite-jdbc's
// native binding, which on this Windows + Android Studio JBR combination
// fails with:
//
//     A failure occurred while executing
//       app.cash.sqldelight.gradle.VerifyMigrationTask$VerifyMigrationAction
//     > 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'
//
// (sqlite-jdbc's `.dll` extracts to a temp dir but the JBR's class
// resolution misses the matching native method.) `:gradlew clean build`
// trips this before any of our actual code compiles. Runtime
// `MigrationTest.kt` in `:shared:androidUnitTest` covers the same
// migration ladder via the JVM SQLite driver, so skipping the build-time
// task on Windows is a safe trade — Linux / macOS CI still verifies it.
//
// Tracked: see PRODUCTION_PLAN_NATIVE.md Stage 1.5 for the original
// note + bugs.md MB-200..203 for the outside-review-pass deferrals.
val isWindowsHost = System.getProperty("os.name").lowercase().startsWith("windows")
if (isWindowsHost) {
    tasks.matching {
        it.name.startsWith("verify") && it.name.endsWith("Migration")
    }.configureEach {
        enabled = false
        // Helpful echo on `clean build` so the dev sees why the task was
        // skipped instead of wondering why their migrations were never
        // verified at build time.
        doFirst {
            logger.lifecycle(
                "Skipping $name on Windows — sqlite-jdbc + JBR native-link " +
                    "incompatibility (see :shared:build.gradle.kts comment). " +
                    "Migration coverage runs via :shared:testDebugUnitTest -> MigrationTest.kt.",
            )
        }
    }
}

// ---------------------------------------------------------------------
// iOS compile check
//
// The iOS targets are declared above, but nothing in the Android build
// or in CI ever compiled them — so they rotted silently. Between MK.19.8
// landing `BackupCipher` + `sha256Hex` as `expect` in commonMain and
// 2026-08-10, `iosMain` was missing both `actual`s. That is a hard
// compile error on the iOS targets *only*, which is exactly why an
// Android-only build and an ubuntu-latest CI never surfaced it.
//
// `:shared:checkIosCompile` compiles every iOS target, main and test, so
// the next such gap fails loudly. Kotlin/Native cannot build Apple
// targets off a macOS host, so elsewhere the task fails with an
// explanation rather than a cryptic toolchain error.
//
// Deliberately NOT wired into `check` — that would break every Windows
// dev build and the ubuntu-latest CI job. It is invoked explicitly, and
// by the macos job in .github/workflows/android-tests.yml.
// ---------------------------------------------------------------------
val isMacHost = System.getProperty("os.name").lowercase().startsWith("mac")

tasks.register("checkIosCompile") {
    group = "verification"
    description = "Compiles all iOS targets (main + test). Requires a macOS host."
    if (isMacHost) {
        dependsOn(
            "compileKotlinIosX64",
            "compileKotlinIosArm64",
            "compileKotlinIosSimulatorArm64",
            "compileTestKotlinIosX64",
            "compileTestKotlinIosArm64",
            "compileTestKotlinIosSimulatorArm64",
        )
    } else {
        doFirst {
            error(
                "checkIosCompile requires a macOS host — Kotlin/Native cannot build Apple targets " +
                    "on ${System.getProperty("os.name")}. Run it on a Mac, or let the " +
                    "\"iOS targets compile\" job in .github/workflows/android-tests.yml cover it.",
            )
        }
    }
}
