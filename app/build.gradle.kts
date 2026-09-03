plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.warptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.warptv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures { buildConfig = true }

    // One universal APK: do not split by ABI.
    splits {
        abi { isEnable = false }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 1.0.20260102 is the latest version currently published to Maven Central.
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
