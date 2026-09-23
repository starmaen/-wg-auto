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
        versionCode = 14
        versionName = "1.13"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    // ⚠ لم تتوفر لي إمكانية الوصول للإنترنت للتحقق من رقم إصدار أحدث فعلي على Maven، وقد
    // فشل جلب 1.0.20241018 لعدم وجوده. عدت للإصدار المؤكَّد عمله. تحذير 16 كيلوبايت لن يختفي
    // تماماً حتى تُنشر نسخة أحدث من هذه المكتبة تدعمه رسمياً — الاعتماد الآن على خيار التعبئة
    // أدناه فقط، وهو لا يغطي محاذاة الملفات الداخلية للمكتبة نفسها.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
