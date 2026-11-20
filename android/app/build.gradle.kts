plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "kr.pcab.cam"
    compileSdk = 34
    defaultConfig {
        applicationId = "kr.pcab.cam"
        minSdk = 29
        targetSdk = 34
        versionCode = runNo
        versionName = "1.0.$runNo"
    }
    signingConfigs {
        create("rel") {
            storeFile = file("release.jks")
            storePassword = "pcabcam2026"
            keyAlias = "pcab"
            keyPassword = "pcabcam2026"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    val cam = "1.3.4"
    implementation("androidx.camera:camera-core:$cam")
    implementation("androidx.camera:camera-camera2:$cam")
    implementation("androidx.camera:camera-lifecycle:$cam")
    implementation("androidx.camera:camera-view:$cam")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
