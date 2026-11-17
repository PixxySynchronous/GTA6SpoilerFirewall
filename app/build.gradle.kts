plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.piyush.spoilerfirewall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piyush.spoilerfirewall"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.3-block-by-default"
    }

    buildFeatures {
        buildConfig = true
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
    // Bundled on-device Latin text recognition model; no runtime cloud call.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
