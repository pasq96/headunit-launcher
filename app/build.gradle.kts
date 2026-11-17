plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing: CI/local env vars; without them release falls back to the debug keystore (test signing).
val storePath = System.getenv("KEYSTORE_PATH")?.let { rootProject.file("app").resolve(it).canonicalFile }

android {
    namespace = "com.pasq96.headunit"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pasq96.headunit"
        // PX30/RK3326 head units run Android 10-12 (build.prop API often spoofed): floor 26, target 33.
        minSdk = 26
        targetSdk = 33
        // No native code of our own: filter keeps any transitive .so to the two ABIs these SoCs ship (32/64-bit userland).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    signingConfigs {
        if (storePath?.exists() == true) create("release") {
            storeFile = storePath
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/DEPENDENCIES", "/META-INF/versions/**") }
    // Transitive AndroidX .so files ship as-is (the runner has no NDK to strip them): skip the strip step and its warning.
    packaging { jniLibs.keepDebugSymbols += "**/*.so" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
