plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.4.21"
}

android {
    namespace = "com.sadique.iptv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sadique.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = 11
        versionName = "2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Media3 1.9.2 is the newest stable that still builds against compileSdk 35:
    // 1.11.x requires minCompileSdk 36 (checked in the published AAR metadata).
    val media3Version = "1.9.2"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
}
