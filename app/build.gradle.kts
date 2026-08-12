plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.healthfit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.healthfit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.7"
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
