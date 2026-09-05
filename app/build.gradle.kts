plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.avatr.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.avatr.monitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            // APK-ul de test se instaleaza alaturi de cel final, nu peste el
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = false
            // fara semnatura de lansare configurata, foloseste cheia de test,
            // ca APK-ul sa fie instalabil direct
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WebViewAssetLoader: serveste assets de pe o origine https, ca GPS-ul
    // sa nu fie refuzat tacut de Chromium
    implementation("androidx.webkit:webkit:1.11.0")
}
