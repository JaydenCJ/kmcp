// Reference build script for the android-capabilities sample.
// This module is NOT included in the root settings.gradle.kts because it
// requires the Android Gradle Plugin and an installed Android SDK.
// Copy this module into an Android project to use it.

plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.2.0"
}

android {
    namespace = "dev.kmcp.samples.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kmcp.samples.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Published from the repository root with: ./gradlew publishToMavenLocal
    implementation("dev.kmcp:kmcp:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
