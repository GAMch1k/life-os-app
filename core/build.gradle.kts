plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The default server and the update origin: the build settings
// lifeos.serverUrl and lifeos.updatesUrl, resolved in the root build.gradle.kts.
val lifeosServerUrl: String by rootProject.extra
val lifeosUpdatesUrl: String by rootProject.extra

android {
    namespace = "com.gamch1k.lifeos.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // The lowest of the two apps (wear), so both can depend on core.
        minSdk = libs.versions.wearMinSdk.get().toInt()
        // Read through LifeOsEndpoints, which both apps use.
        buildConfigField("String", "SERVER_URL", "\"$lifeosServerUrl\"")
        buildConfigField("String", "UPDATES_URL", "\"$lifeosUpdatesUrl\"")
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
