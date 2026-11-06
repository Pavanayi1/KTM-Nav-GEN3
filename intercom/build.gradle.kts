// Rider Intercom, merged into Navigator as a library module.
//
// The standalone project was built on Kotlin 2.0.21 with the kotlin.plugin.compose
// compiler, AGP 8.7.3 and compileSdk 35. One Gradle build can only carry one Kotlin
// plugin version, so this module is aligned DOWN to Navigator's toolchain (Kotlin
// 1.9.24, Compose compiler 1.5.14) rather than moving all of Navigator up to K2 just
// before a ride test. The 33 JVM tests under src/test are what prove the intercom
// survived the move: run `./gradlew :intercom:testDebugUnitTest`.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mensa.riderintercom"
    compileSdk = 34

    defaultConfig {
        // Navigator's floor. The standalone app said 29; whether anything below 29
        // is actually called unguarded is settled by lint's NewApi check, and the
        // intercom entry point in Navigator is gated on the result.
        minSdk = 26
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Same BOM as Navigator: one app, one Compose version.
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // MediaSessionCompat + MediaButtonReceiver for the headset button.
    implementation("androidx.media:media:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
