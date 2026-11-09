import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself ("built-in Kotlin") with the Kotlin
        // Gradle plugin found on the build classpath; this pins its version.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// ---- Version: one versionName/versionCode for both apps (version.properties)

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val appVersionName: String = versionProperties.getProperty("versionName")
    ?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
    ?: throw GradleException("version.properties: versionName must be MAJOR.MINOR.PATCH")
val appVersionCode: Int = versionProperties.getProperty("versionCode")?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: throw GradleException("version.properties: versionCode must be a positive integer")

// ---- Servers: where the apps go (lifeos.serverUrl, lifeos.updatesUrl)
//
// Two build settings, compiled into core's BuildConfig, which both apps read
// through core/…/config/LifeOsEndpoints.kt. Each is a Gradle property, else an
// environment variable, else the official Life OS server:
//
//   lifeos.serverUrl   LIFE_OS_PUBLIC_URL   the server the phone offers at
//                                           onboarding; the owner may change
//                                           it in the app at any time
//   lifeos.updatesUrl  LIFE_OS_UPDATES_URL  where the phone and the watch get
//                                           app updates, whatever server the
//                                           owner chose
//
// Each must be an https:// origin: a host and perhaps a port, nothing after it.

val officialServerUrl = "https://lifeos.gamch1k.com"

fun originSetting(property: String, variable: String): String {
    val value = listOf(providers.gradleProperty(property), providers.environmentVariable(variable))
        .firstNotNullOfOrNull { setting -> setting.orNull?.trim()?.takeIf { it.isNotEmpty() } }
        ?: return officialServerUrl
    return value.trimEnd('/')
        .takeIf { Regex("""https://[a-z0-9.-]+(:[0-9]{1,5})?""", RegexOption.IGNORE_CASE).matches(it) }
        ?.lowercase()
        ?: throw GradleException(
            "$property ($variable) must be an https:// origin such as $officialServerUrl: " +
                "a host and perhaps a port, no path, query or user name",
        )
}

val lifeosServerUrl: String by extra(originSetting("lifeos.serverUrl", "LIFE_OS_PUBLIC_URL"))
val lifeosUpdatesUrl: String by extra(originSetting("lifeos.updatesUrl", "LIFE_OS_UPDATES_URL"))

// ---- Release signing
//
// Every APK that is ever installed (release and debug alike) is signed with
// the release key, so updates and the phone–watch pairing never break.
// LIFEOS_KEYSTORE_PROPERTIES names a properties file with storeFile (relative
// to that file), storeType, storePassword, keyAlias and keyPassword;
// tools/build mounts it read-only. Without it, compiling and unit tests work,
// but packaging an APK fails before any task runs, unless
// -Plifeos.unsignedApks=true (CI) asks for unsigned APKs, which Android
// refuses to install.

class ReleaseSigning(val storeFile: File, val storeType: String, val storePassword: String, val keyAlias: String, val keyPassword: String)

val releaseSigning: ReleaseSigning? = providers.environmentVariable("LIFEOS_KEYSTORE_PROPERTIES").orNull?.let { path ->
    val propertiesFile = File(path)
    if (!propertiesFile.isFile) {
        throw GradleException("LIFEOS_KEYSTORE_PROPERTIES=$path is not a readable file")
    }
    val properties = Properties().apply { propertiesFile.inputStream().use(::load) }
    fun required(key: String): String = properties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("$path has no value for '$key'")
    val storeFile = File(required("storeFile")).let { if (it.isAbsolute) it else File(propertiesFile.parentFile, it.path) }
    if (!storeFile.isFile) {
        throw GradleException("the keystore named in $path (storeFile) does not exist")
    }
    ReleaseSigning(
        storeFile = storeFile,
        storeType = properties.getProperty("storeType")?.takeIf { it.isNotBlank() } ?: "PKCS12",
        storePassword = required("storePassword"),
        keyAlias = required("keyAlias"),
        keyPassword = required("keyPassword"),
    )
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            defaultConfig {
                versionCode = appVersionCode
                versionName = appVersionName
            }
            if (releaseSigning != null) {
                signingConfigs.create("release") {
                    storeFile = releaseSigning.storeFile
                    storeType = releaseSigning.storeType
                    storePassword = releaseSigning.storePassword
                    keyAlias = releaseSigning.keyAlias
                    keyPassword = releaseSigning.keyPassword
                }
            }
            buildTypes {
                getByName("release") { signingConfig = signingConfigs.findByName("release") }
                getByName("debug") { signingConfig = signingConfigs.findByName("release") }
            }
        }
    }
}

// CI (.github/workflows/ci.yml, tools/build ci) builds the APKs without the
// key: -Plifeos.unsignedApks=true lets packaging run with no signing config at
// all, so the APKs come out unsigned (<module>-release-unsigned.apk). Android
// refuses to install an unsigned APK, so whatever can be installed still
// carries the release key.
val unsignedApks = providers.gradleProperty("lifeos.unsignedApks").orNull == "true"

if (releaseSigning == null && !unsignedApks) {
    val packagingTask = Regex("""^(package|bundle)(Debug|Release)$""")
    gradle.taskGraph.whenReady {
        val packaging = allTasks.filter { packagingTask.matches(it.name) }.map { it.path }
        if (packaging.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured, so no APK can be built (${packaging.joinToString()}). " +
                    "Every installable APK must be signed with the release key: run tools/build, or set " +
                    "LIFEOS_KEYSTORE_PROPERTIES to the keystore.properties path.",
            )
        }
    }
}
