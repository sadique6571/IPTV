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
        // This is a DEBUG key only — the release build uses its own keystore
        // from the environment (see "release" below). Reuse this file for
        // release signing only if that was the explicit choice.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
        // Release signing: the keystore comes from the environment (CI decodes
        // it from the RELEASE_KEYSTORE_BASE64 secret — see
        // .github/workflows/android.yml). Nothing is committed here, so this
        // block works for BOTH choices: a brand-new release key, or the debug
        // key reused for release signing. A missing keystore fails the
        // release build loudly instead of silently signing with the wrong key.
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            // R8 + resource shrinking (v2.0.22): expect ~20-30% smaller APK
            // (5.33 MB debug -> ~4 MB). Rules live in app/proguard-rules.pro.
            // Media3 / androidx.core ship their own consumer rules in their
            // AARs, so R8 already keeps what they need.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    defaultConfig {
        applicationId = "com.sadique.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = 76
        versionName = "2.0.28"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
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

val apkSigning = android.signingConfigs.getByName("debug")
tasks.register<Exec>("compactDebugApk") {
    dependsOn("assembleDebug")
    val sdk = file(requireNotNull(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")))
    val tools = sdk.resolve("build-tools/35.0.0")
    val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val published = layout.buildDirectory.file("outputs/compact/IPTV-debug.apk")
    doFirst {
        environment("APK_STORE_PASS", requireNotNull(apkSigning.storePassword))
        environment("APK_KEY_PASS", requireNotNull(apkSigning.keyPassword))
        environment("APK_ALIAS", requireNotNull(apkSigning.keyAlias))
    }
    commandLine("python3", rootProject.file("scripts/compact_apk.py"), debugApk.get().asFile,
        published.get().asFile, tools, requireNotNull(apkSigning.storeFile))
}

// Release twin of compactDebugApk: R8 build, same recompress + align +
// re-sign pass. Output is IPTV.apk (not IPTV-debug.apk): UpdateChecker
// looks for exactly that asset name since v2.0.22. The compact script
// verifies the re-signed APK keeps the same certificate as the R8 APK, so
// the keystore here MUST be the release one (env vars above) — a mismatch
// fails the build instead of shipping a wrongly-signed APK.
val releaseSigning = android.signingConfigs.getByName("release")
tasks.register<Exec>("compactReleaseApk") {
    dependsOn("assembleRelease")
    val sdk = file(requireNotNull(System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")))
    val tools = sdk.resolve("build-tools/35.0.0")
    val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val published = layout.buildDirectory.file("outputs/compact/IPTV.apk")
    doFirst {
        environment("APK_STORE_PASS", requireNotNull(releaseSigning.storePassword) { "RELEASE_KEYSTORE_PASSWORD is not set" })
        environment("APK_KEY_PASS", requireNotNull(releaseSigning.keyPassword) { "RELEASE_KEY_PASSWORD is not set" })
        environment("APK_ALIAS", requireNotNull(releaseSigning.keyAlias) { "RELEASE_KEY_ALIAS is not set" })
    }
    commandLine("python3", rootProject.file("scripts/compact_apk.py"), releaseApk.get().asFile,
        published.get().asFile, tools,
        requireNotNull(releaseSigning.storeFile) { "RELEASE_KEYSTORE_PATH is not set" })
}
