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
        applicationId = "com.navigator.app"
        manifestPlaceholders["ktmLauncherLabel"] = "KTM Navigator"
        manifestPlaceholders["husqvarnaLauncherLabel"] = "Husqvarna Navigator"
        minSdk = 26
        targetSdk = 34
        // Days-since-epoch: monotonically increasing across builds, so every
        // new APK is a clean in-place update (Play Protect also treats a
        // never-incrementing versionCode as a suspicion signal).
        versionCode = (System.currentTimeMillis() / 86_400_000L).toInt()
        // Public beta line, with a build stamp appended so it's easy to confirm
        // which build is actually installed on-device (Settings > Diagnostics).
        versionName = "0.2.0-beta+" + SimpleDateFormat("MMdd-HHmm").format(Date())
        manifestPlaceholders["appLabel"] = "Navigator Gen3"
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
            // No minification: TFLite + reflection-heavy BLE code isn't worth
            // proguard risk for a sideloaded app; signing is what matters here.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Preserve distinct launcher labels when combined with the optional field-test build.
    buildTypes.configureEach {
        if (name == "debug" && providers.gradleProperty("husqvarnaTestBuild").orNull == "true") {
            manifestPlaceholders["ktmLauncherLabel"] = "KTM Navigator Test"
            manifestPlaceholders["husqvarnaLauncherLabel"] = "Husqvarna Navigator Test"
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
    testImplementation("junit:junit:4.13.2")
    // On-device maneuver-icon classifier (ported from the KTM Gen-3 companion
    // app's approach): a small TFLite CNN recognises the nav app's turn-icon
    // bitmap and outputs the dash turn-icon code.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
