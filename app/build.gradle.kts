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
        versionCode = 1
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
