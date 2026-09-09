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

    /*
     * O cheie a noastra, tinuta in depozit, nu cheia de test a masinii care
     * compileaza.
     *
     * Asta e motivul pentru care setarile se pierdeau la fiecare versiune
     * noua: cheia de test se face din nou pe fiecare runner de GitHub, deci
     * fiecare APK avea alta semnatura, Android refuza sa il puna peste cel
     * vechi, iar dezinstalarea stergea SharedPreferences si localStorage —
     * adica adresa serverului, cheia lui, istoricul si coada de fisiere.
     *
     * Cu fisierul de mai jos, semnatura e mereu aceeasi si versiunile se
     * instaleaza una peste alta, cu setarile la locul lor.
     */
    signingConfigs {
        create("aMea") {
            storeFile = file("cheia-mea.jks")
            storePassword = "avatr123"
            keyAlias = "avatr"
            keyPassword = "avatr123"
        }
    }

    buildTypes {
        debug {
            // APK-ul de test se instaleaza alaturi de cel final, nu peste el
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            signingConfig = signingConfigs.getByName("aMea")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aMea")
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
    // Android Auto: sabloane pentru ecranul masinii (nu exista WebView acolo).
    //
    // Amandoua sunt necesare, si asta e capcana. "app-projected" e doar puntea
    // catre Android Auto; clasele in sine — CarAppService, Screen, Session,
    // sabloanele — stau in "app". El il aduce pe "app" cu sine, dar numai
    // pentru rulare, nu si pentru compilare. Cu o singura linie, aplicatia se
    // asambleaza si totusi compilatorul nu vede nimic din biblioteca, exact
    // cum s-a intamplat: toate simbolurile nerezolvate deodata.
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
