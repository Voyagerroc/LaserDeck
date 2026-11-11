import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// İmzalama bilgileri depoya girmez: android/keystore.properties (gitignore'da)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.voyagerroc.laserdeck.ptt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voyagerroc.laserdeck.ptt"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "2.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
                // Yan yükleme (site üzerinden kurulum) için tüm şemalarla imzala.
                // Varsayılanda yalnızca v2 üretiliyordu; bazı OEM doğrulayıcıları
                // ve eski kurulum akışları v1 ya da v3 bekliyor.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    // android.jar'daki org.json yalnızca boş bir taslak; gerçek uygulama olmadan
    // MessageStore birim testinde çalışamaz.
    testImplementation("org.json:json:20240303")
}
