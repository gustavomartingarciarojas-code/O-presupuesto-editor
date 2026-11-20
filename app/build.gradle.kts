plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ar.gustavo.cerobudget"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.gustavo.cerobudget"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Firma fija: así cada versión nueva se instala encima de la anterior
    signingConfigs {
        create("fija") {
            storeFile = file("firma.jks")
            storePassword = "0budget0"
            keyAlias = "0budget"
            keyPassword = "0budget0"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fija")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
