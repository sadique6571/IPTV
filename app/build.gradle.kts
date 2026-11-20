plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.4.21"
}

android {
    namespace = "com.sadique.iptv"
    compileSdk = 35
    signingConfigs {
        // Fixed debug key: every CI build signs with the same key (app/debug.keystore,
        // PKCS12, alias androiddebugkey, password android), so updates install cleanly
        // over older builds. Before this, each ephemeral CI runner generated a fresh
        // random debug key, which caused INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        // This is a DEBUG key only — never use it for a release build.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }
    defaultConfig {
        applicationId = "com.sadique.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = 41
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
    // HLS and DASH are SEPARATE Media3 modules — the core exoplayer artifact does
    // NOT include them. Without exoplayer-hls, opening any .m3u8 URL crashes the
    // app instantly: DefaultMediaSourceFactory throws when the module is missing
    // (verified 2026-11-18: MUN TV played only because its extensionless URL fell
    // back to the progressive MPEG-TS path).
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    // FileProvider (UpdateChecker installs downloaded APKs through it).
    // androidx.core is ALREADY a runtime transitive dependency of
    // media3-exoplayer (verified in its 1.9.2 module metadata: runtime-only,
    // never api) — so this declaration adds no new code to the APK, it only
    // makes the already-present library visible at compile time. Without it,
    // `import androidx.core.content.FileProvider` fails to compile.
    implementation("androidx.core:core:1.13.1")
}
