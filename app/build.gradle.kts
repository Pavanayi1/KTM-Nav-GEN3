import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

// Release signing credentials live in local.properties (gitignored) so the
// keystore identity stays stable across builds - a consistent, non-debug
// signature is what keeps Play Protect from hard-blocking sideloaded installs.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.navigator.app"
    compileSdk = 34

    defaultConfig {
        // App package ID. namespace (code package) stays com.navigator.app so the
        // Kotlin sources don't all need re-packaging; only the installed app ID
        // changes to the Navigator name.
        applicationId = "com.navigator.app"
        minSdk = 26
        targetSdk = 34
        // Days-since-epoch: monotonically increasing across builds, so every
        // new APK is a clean in-place update (Play Protect also treats a
        // never-incrementing versionCode as a suspicion signal).
        versionCode = (System.currentTimeMillis() / 86_400_000L).toInt()
        // Public beta line, with a build stamp appended so it's easy to confirm
        // which build is actually installed on-device (Settings > Diagnostics).
        versionName = "0.3.0-beta+" + SimpleDateFormat("MMdd-HHmm").format(Date())

        // Google Maps key for the Ride Analytics map (optional): add
        // MAPS_API_KEY=... to local.properties. Without it the analytics
        // screen falls back to the built-in canvas route sketch.
        val mapsKey = localProps.getProperty("MAPS_API_KEY") ?: ""
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
    }

    signingConfigs {
        create("release") {
            localProps.getProperty("RELEASE_STORE_FILE")?.let { path ->
                storeFile = file(path)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 on, for speed rather than size: unoptimised Compose is the
            // single biggest source of UI jank (measured ~12x slower median
            // frame on the emulator without compiled code). proguard-rules.pro
            // keeps ALL of our own classes and TFLite as-is, so the old worry
            // (TFLite + reflection-heavy BLE code) doesn't apply - only the
            // libraries get shrunk and optimised.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // On-device maneuver-icon classifier (ported from the KTM Gen-3 companion
    // app's approach): a small TFLite CNN recognises the nav app's turn-icon
    // bitmap and outputs the dash turn-icon code.
    implementation(project(":intercom"))
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Ride Analytics map (traffic layer + zoom). Needs MAPS_API_KEY in
    // local.properties at runtime; without one the screen self-degrades to
    // the canvas sketch, so the dependency is safe to ship regardless.
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-compose:4.4.1")
    // HeatmapTileProvider for the aggregated traffic heatmap.
    implementation("com.google.maps.android:android-maps-utils:3.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Installs the baseline profile (src/main/baseline-prof.txt + the ones the
    // Compose libraries ship) on sideloaded installs too, so ART AOT-compiles
    // the UI path instead of interpreting it on first runs.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
