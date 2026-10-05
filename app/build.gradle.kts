plugins {
    id("com.android.application")
}

android {
    namespace = "com.jeevesh415.blutoothconnector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jeevesh415.blutoothconnector"
        minSdk = 29
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

    signingConfigs {
        if (releaseKeystorePath != null &&
            releaseKeystorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePath != null &&
                releaseKeystorePassword != null &&
                releaseKeyAlias != null &&
                releaseKeyPassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Android 10 (API 29) is the minimum supported OS.
    // API 36 is used as the stable compilation/target baseline; Android 17 remains
    // runtime-compatible without requiring the Android 17 preview SDK package.
}

dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    testImplementation("junit:junit:4.13.2")
}