plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aidexx.reader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aidexx.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = 99
        versionName = "0.1.60.20"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // No fallback signing key: releases must match the certificate already on the phone.
    val releaseKey = System.getenv("GLUCOTRAIL_KEYSTORE")
    if (!releaseKey.isNullOrBlank()) {
        signingConfigs.create("githubRelease") {
            storeFile = file(releaseKey)
            storePassword = System.getenv("GLUCOTRAIL_STORE_PASSWORD")
            keyAlias = System.getenv("GLUCOTRAIL_KEY_ALIAS")
            keyPassword = System.getenv("GLUCOTRAIL_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("githubRelease")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
