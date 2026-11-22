plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The update origin and the update repository: the build settings
// lifeos.updatesUrl and lifeos.updatesRepo, resolved in the root
// build.gradle.kts. There is no default server (P22-13).
val lifeosUpdatesUrl: String by rootProject.extra
val lifeosUpdatesRepo: String by rootProject.extra

android {
    namespace = "com.gamch1k.lifeos.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // The lowest of the two apps (wear), so both can depend on core.
        minSdk = libs.versions.wearMinSdk.get().toInt()
        // Read through LifeOsEndpoints, which both apps use.
        buildConfigField("String", "UPDATES_URL", "\"$lifeosUpdatesUrl\"")
        buildConfigField("String", "UPDATES_REPO", "\"$lifeosUpdatesRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.cronet.bundled)
    api(libs.kotlinx.coroutines.android)
    // The client's DTOs and the stores are part of core's API for the apps.
    api(libs.kotlinx.serialization.json)
    api(libs.androidx.datastore.preferences)

    // The phone–watch link (core/…/wearlink): both apps talk over the Wear OS
    // Data Layer, so its API is part of core's.
    api(libs.play.services.wearable)
    api(libs.kotlinx.coroutines.play.services)

    // Pairing (P21-06): the pairing QR code read from a photo (core/…/pairing/QrCodes.kt).
    // Only the phone uses it; R8 leaves it out of the wear APK.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
