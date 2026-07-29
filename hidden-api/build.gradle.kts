plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.jizizr.signaldock.hiddenapi"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
