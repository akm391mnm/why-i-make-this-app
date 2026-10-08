plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.autoclicker"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // ใน GitHub Actions ใช้เลขรอบ build เป็น versionCode ให้เพิ่มขึ้นเองทุกครั้ง
    // (จำเป็นต่อระบบอัปเดตในแอป ที่เช็คว่า versionCode เพิ่มขึ้นหลังติดตั้ง)
    val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "com.example.autoclicker"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$runNumber"

        // OpenCV AAR ใหญ่ — เก็บเฉพาะสถาปัตยกรรมมือถือทั่วไป เพื่อให้ APK เล็กลง
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = false
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.opencv:opencv:4.9.0")
}
