plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gamch1k.lifeos.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // Same package and signing key as the phone app (Wear OS Data Layer requirement).
        applicationId = "com.gamch1k.lifeos"
        minSdk = libs.versions.wearMinSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode/versionName come from version.properties (root build.gradle.kts).

        // Embedded Cronet (direct calls when the phone is away, the update's
        // own download) ships native code per ABI. One ABI keeps the APK the
        // phone streams over Bluetooth small; since 0.4.5 (BUG-45) it is
        // armeabi-v7a: the Galaxy Watch 8 (Wear OS 6) runs 32-bit apps only
        // and reports armeabi-v7a, armeabi. 0.4.3 and 0.4.4 carried arm64-v8a
        // only, which Android there refuses (INSTALL_FAILED_NO_MATCHING_ABIS),
        // and the phone, which checks the watch's ABIs before it sends an
        // update (WatchAbi in core), never sent them. Keep in step with
        // WatchAbi.APK_ABIS (WearApkAbiTest compares them through BuildConfig);
        // tools/builder/in-container fails a release without lib/armeabi-v7a/.
        val apkAbis = listOf("armeabi-v7a")
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "APK_ABIS", "\"${apkAbis.joinToString(",")}\"")
    }

    packaging {
        // Compressed native code and dex (extracted at install), unlike AGP's
        // default of storing both uncompressed: the APK the phone sends over
        // Bluetooth shrinks from 16.6 MB (0.4.2) to 5.9 MB (0.4.5, armeabi-v7a
        // only); the watch has the space. resources.arsc stays uncompressed
        // (Android requires it).
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Off: the shrinker removed array/android_wear_capabilities, which Play
            // services reads by name, even with res/raw/keep.xml (0.4.0, 0.4.1
            // builds); the APK is mostly native code, so it saves little.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The phone–watch link (wearlink), the Cronet engine configuration, the endpoints.
    implementation(project(":core"))
    implementation(libs.cronet.bundled)

    implementation(platform(libs.compose.bom))
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.navigation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.complications.data.source.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
