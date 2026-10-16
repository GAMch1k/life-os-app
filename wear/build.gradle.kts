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
        // own download) ships native code per ABI. Since 0.4.3 only arm64-v8a:
        // the Galaxy Watch 8 runs a 64-bit user space, and the phone doesn't
        // push this APK to a watch without it (WatchAbi in core). Keep in step
        // with WatchAbi.APK_ABIS.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging {
        // Compressed native libraries (extracted at install): the APK the phone
        // sends over Bluetooth shrinks by about half. The watch has the space.
        jniLibs { useLegacyPackaging = true }
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
