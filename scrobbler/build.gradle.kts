import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// AK Scrobbler: aplicație companion, care citește ce redă orice player
// (prin sesiunile media ale Android) și trimite titlul + minutul către AK VOD Unify.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val appVersionCode = (System.getenv("VERSION_CODE") ?: localProps.getProperty("VERSION_CODE"))?.toIntOrNull() ?: 1
val appVersionName = System.getenv("VERSION_NAME") ?: localProps.getProperty("VERSION_NAME") ?: "0.1.0"
val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH") ?: localProps.getProperty("SIGNING_KEYSTORE_PATH")

/** Pachetul aplicației principale către care trimitem (debug ↔ debug, release ↔ release). */
fun mainAppId(debug: Boolean) = if (debug) "ro.ak.vodunify.debug" else "ro.ak.vodunify"

android {
    namespace = "ro.ak.vodscrobbler"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.ak.vodscrobbler"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: localProps.getProperty("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: localProps.getProperty("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: localProps.getProperty("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            val main = mainAppId(debug = false)
            buildConfigField("String", "MAIN_APP_ID", "\"$main\"")
            manifestPlaceholders["mainAppId"] = main
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            val main = mainAppId(debug = true)
            buildConfigField("String", "MAIN_APP_ID", "\"$main\"")
            manifestPlaceholders["mainAppId"] = main
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":importer"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
