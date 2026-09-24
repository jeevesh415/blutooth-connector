plugins {
    id("com.android.application")
}

android {
    namespace = "com.jeevesh415.blutoothconnector"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jeevesh415.blutoothconnector"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.6.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    testImplementation("junit:junit:4.13.2")
}
