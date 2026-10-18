plugins {
    id("com.android.application")
}

android {
    namespace = "org.aaaasta03.astrabrowser"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.aaaasta03.astrabrowser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:158.0.20261007201254")

    testImplementation("junit:junit:4.13.2")
}
