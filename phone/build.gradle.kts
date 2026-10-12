plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gamch1k.lifeos.phone"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        // Shared with the wear app: the Wear OS Data Layer requires one package and one signing key.
        applicationId = "com.gamch1k.lifeos"
        minSdk = libs.versions.phoneMinSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode/versionName come from version.properties (root build.gradle.kts).

        // Embedded Cronet ships native code per ABI (~7 MB each); the phone is arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle's Java resources: post-quantum tables (1.2 MB) and
            // certificate-path messages. The watch setup uses neither.
            excludes += listOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/pkix/CertPathReviewerMessages*.properties",
                "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.browser)
    implementation(libs.unifiedpush.connector)
    implementation(libs.unifiedpush.embedded.fcm.distributor)
    implementation(libs.androidx.glance.appwidget)

    // The watch setup (P19-06): pairing with and installing onto the watch over Wireless debugging.
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)
}
