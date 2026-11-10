plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val gitSha: String = System.getenv("GITHUB_SHA")?.take(7) ?: "dev"

android {
    namespace = "dev.xdvdfs.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.xdvdfs.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.9.0"

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded builds: sign with the debug key so the APK installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Lint is informational here; it must not fail sideloaded CI builds.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
}
