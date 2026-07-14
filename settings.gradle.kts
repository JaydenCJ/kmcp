pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kmcp"

// The Android reference sample under samples/android-capabilities is
// intentionally not included in this build: compiling it requires the
// Android Gradle Plugin and an installed Android SDK. See its README.
