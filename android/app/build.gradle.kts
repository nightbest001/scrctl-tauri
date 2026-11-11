plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.scrctl.link"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.scrctl.link"
        minSdk = 26
        targetSdk = 34
        // Diisi dari tag git lewat workflow: -PappVersion=0.6.1 -PappCode=601
        versionCode = (project.findProperty("appCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersion") as String?) ?: "0.1.0"
    }

    // Key tetap (di-commit) supaya tiap APK baru bisa menimpa APK lama.
    // Debug key berubah tiap build di GitHub, bikin update gagal "app not installed".
    signingConfigs {
        create("scrctl") {
            storeFile = file("scrctl-release.jks")
            storePassword = "scrctl123"
            keyAlias = "scrctl"
            keyPassword = "scrctl123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("scrctl")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR: zxing core membuat gambar QR; Google code scanner memindai tanpa izin kamera
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
