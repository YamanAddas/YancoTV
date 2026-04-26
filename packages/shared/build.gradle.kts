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
    ignoreFailures.set(true)
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
