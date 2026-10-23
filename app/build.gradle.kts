plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version comes from the release tag (vX.Y.Z), passed by CI as -PappVersion=X.Y.Z.
val appVersion: String = (project.findProperty("appVersion") as String?) ?: "0.0.0"
val appVersionCode: Int = appVersion.split(".").let { parts ->
    val (major, minor, patch) = (parts + listOf("0", "0", "0")).take(3).map { it.toIntOrNull() ?: 0 }
    major * 10000 + minor * 100 + patch
}.coerceAtLeast(1)

// Release signing is configured only when the keystore is provided by CI (environment variables).
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "me.csepregi.pkmrec"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.csepregi.pkmrec"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        buildConfigField("String", "GITHUB_REPO", "\"pcsepregi/PKMrec\"")

        // Only 64-bit ARM phones (e.g. OnePlus 13); keeps the ML Kit native libraries small.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signingKeystorePath != null) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
