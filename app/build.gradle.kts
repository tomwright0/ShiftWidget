plugins {
    id("com.android.application")
}

android {
    namespace = "com.shiftcal.widget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shiftcal.widget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
