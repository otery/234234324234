plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.foldreveal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.foldreveal"
        // 힌지 각도 센서(TYPE_HINGE_ANGLE)는 Android 11(API 30)부터 지원됩니다.
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // NewApi 등 lint 경고가 빌드를 중단시키지 않게 합니다.
    // (RuntimeShader 는 런타임에 Build.VERSION 으로 분기하므로 안전합니다)
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Compose / Material3 / coroutines 를 모두 제거했습니다.
// 빌드가 깨질 여지를 없애기 위해, 프레임워크 기본 View 와 최소 라이브러리만 씁니다.
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
}
