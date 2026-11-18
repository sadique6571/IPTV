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
        versionCode = 10
        versionName = "1.9"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
