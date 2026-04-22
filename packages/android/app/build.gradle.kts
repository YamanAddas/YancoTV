plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
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
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
            )
        }
    }

    lint {
        // Launcher + banner assets are the MK.12 pass; the manifest
        // already documents the placeholder state. Keeping this as a
        // blocking lint error would fail CI on every run until then.
        disable += "MissingTvBanner"
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

    // Coil 3 images
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // WorkManager — background sync for sources (MK.3.3)
    implementation(libs.androidx.work.runtime.ktx)

    // ───── test ─────
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
