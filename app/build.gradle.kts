plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.primecircle.lisadictee"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.primecircle.lisadictee"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
    }
    // Signature fixe : chaque nouvelle version s'installe par-dessus l'ancienne, sans désinstaller.
    signingConfigs {
        create("lisa") {
            storeFile = rootProject.file("signature.jks")
            storePassword = "lisadictee"
            keyAlias = "lisa"
            keyPassword = "lisadictee"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("lisa") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("lisa") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
