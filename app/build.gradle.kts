plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.remotecheck"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.remotecheck"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
}
