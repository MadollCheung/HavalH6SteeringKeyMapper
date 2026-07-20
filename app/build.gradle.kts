plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.haval.h6.steeringmapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.haval.h6.steeringmapper"
        // 哈弗H6三代车机一般运行 Android 10（API 29）或 Android 11（API 30）
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // 车机调试阶段先不混淆，方便 logcat 排查
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 关闭 Lint 的无障碍 ContentDescription 检查（车机场景不适用通用规则）
    lint {
        disable += "ContentDescription"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // JSON 序列化（用于映射配置持久化）
    implementation("com.google.code.gson:gson:2.10.1")
}
