plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.roomxxxvoice.kws"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    androidResources {
        noCompress += listOf("onnx", "txt", "phone")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    // 使用 compileOnly 避免 "Direct local .aar file dependencies are not supported" 错误
    // 运行时需确保 App 模块也引入了此 AAR
    compileOnly(files("libs/sherpa-onnx-1.12.20.aar"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
