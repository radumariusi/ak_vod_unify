import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// AK Import: te conectezi în contul serviciului (Netflix, Prime Video) și aduci istoricul în AK VOD Unify.
// Două variante cu același cod și pachete diferite, ca să le poți pune pe rute VPN diferite:
//  - AK Import RO (ro.ak.vodimport.ro): merge direct;
//  - AK Import US (ro.ak.vodimport.us): o pui o singură dată în tunelul VPN US.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val appVersionCode = (System.getenv("VERSION_CODE") ?: localProps.getProperty("VERSION_CODE"))?.toIntOrNull() ?: 1
val appVersionName = System.getenv("VERSION_NAME") ?: localProps.getProperty("VERSION_NAME") ?: "0.1.0"
val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH") ?: localProps.getProperty("SIGNING_KEYSTORE_PATH")

fun mainAppId(debug: Boolean) = if (debug) "ro.ak.vodunify.debug" else "ro.ak.vodunify"

android {
    namespace = "ro.ak.vodimport"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.ak.vodimport"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    flavorDimensions += "route"
    productFlavors {
        create("ro") {
            dimension = "route"
            applicationIdSuffix = ".ro"
            buildConfigField("String", "ROUTE", "\"RO\"")
            resValue("string", "app_name", "AK Import RO")
        }
        create("us") {
            dimension = "route"
            applicationIdSuffix = ".us"
            buildConfigField("String", "ROUTE", "\"US\"")
            resValue("string", "app_name", "AK Import US")
        }
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
        resValues = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation(libs.junit)
}
