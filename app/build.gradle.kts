import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.solartracker.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.solartracker.pro"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.19.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional Google Places key (city autocomplete). Read from the environment (GitHub Secret MAPS_API_KEY in
        // CI) or from the untracked local.properties – never committed. Empty = keyless search (Open-Meteo).
        // A key shipped in an APK can be extracted: it must be restricted to this app (package + SHA-1) and to
        // the Places API in Google Cloud, with a quota cap – see docs/GOOGLE_PLACES_SETUP.md.
        val localProps = Properties()
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { localProps.load(it) }
        val mapsKey = (System.getenv("MAPS_API_KEY") ?: localProps.getProperty("MAPS_API_KEY") ?: "")
            .trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsKey\"")
    }

    // Release signing key comes only from the environment (GitHub Secrets in CI), never from the repo.
    // Without it the release build falls back to the debug key, which cannot update earlier installs.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.osmdroid)
    implementation(libs.usb.serial)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
