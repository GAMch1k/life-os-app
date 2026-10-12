plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gamch1k.lifeos.core"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // The lowest of the two apps (wear), so both can depend on core.
        minSdk = libs.versions.wearMinSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.cronet.bundled)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
