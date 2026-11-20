plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amika.aipro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amika.aipro"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "11.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.11.0")

    // موتور صوتی آفلاین فارسی (Sherpa-ONNX)
    implementation(files("libs/sherpa-onnx.aar"))

    // هسته دانلودر همه‌کاره (yt-dlp + FFmpeg + aria2c)
    val ytdlVersion = "0.17.2"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdlVersion")
}
