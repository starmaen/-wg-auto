plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.star.wgauto"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.star.wgauto"
        minSdk = 26
        targetSdk = 34
        versionCode = 51
        versionName = "1.50"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("stable") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "wgauto"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // أحدث إصدار مؤكَّد على Maven Central (تحقّق منه المستخدم مباشرة، تاريخ النشر 2026-01-02):
    // https://central.sonatype.com/artifact/com.wireguard.android/tunnel/versions
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // OpenVPN مدمج داخل التطبيق (نواة ics-openvpn المفتوحة GPLv2 — بلا تطبيق خارجي وبلا NDK)
    implementation(project(":vpnLib"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    // نسخة متوافقة مع صفحات 16KB (الأقدم تظهر تحذير توافق وتسقط على أجهزة 16K)
    implementation("androidx.graphics:graphics-path:1.1.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
