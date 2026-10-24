plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dk.irvold.gongelandet.smsgateway"
    compileSdk = 36

    defaultConfig {
        applicationId = "dk.irvold.gongelandet.smsgateway"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        // Ingen indbygget server: adressen indtastes i appen. -PapiBase=… forudfylder den (fx til emulatortest).
        val apiBase = (project.findProperty("apiBase") as String?) ?: ""
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Kun til sideloading på den ene telefon (Google Play tillader ikke apps, der sender SMS
            // uden at være standard-SMS-app) — derfor debug-nøglen, ligesom GL-Kiosk.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
