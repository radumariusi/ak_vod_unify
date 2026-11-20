import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Secretele vin din local.properties (local) sau din variabile de mediu (CI). Nu se commit-uiesc niciodată.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// versionCode / versionName pot fi suprascrise de workflow-ul de release.
val appVersionCode = (System.getenv("VERSION_CODE") ?: localProps.getProperty("VERSION_CODE"))?.toIntOrNull() ?: 1
val appVersionName = System.getenv("VERSION_NAME") ?: localProps.getProperty("VERSION_NAME") ?: "0.1.0"

val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH") ?: localProps.getProperty("SIGNING_KEYSTORE_PATH")

android {
    namespace = "ro.ak.vodunify"
    compileSdk = 35

    defaultConfig {
        applicationId = "ro.ak.vodunify"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "TMDB_API_KEY", quoted(secret("TMDB_API_KEY")))
        buildConfigField("String", "TRAKT_CLIENT_ID", quoted(secret("TRAKT_CLIENT_ID")))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", quoted(secret("TRAKT_CLIENT_SECRET")))
        buildConfigField("String", "SIMKL_CLIENT_ID", quoted(secret("SIMKL_CLIENT_ID")))
        buildConfigField("String", "SIMKL_CLIENT_SECRET", quoted(secret("SIMKL_CLIENT_SECRET")))
        buildConfigField("String", "WETRAKR_API_KEY", quoted(secret("WETRAKR_API_KEY")))
        buildConfigField("String", "UPDATE_REPO", quoted("radumariusi/ak_vod_unify"))
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
