import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation("androidx.camera:camera-core:1.3.1")
            implementation("androidx.camera:camera-lifecycle:1.3.1")
            implementation("androidx.camera:camera-camera2:1.3.1")
            implementation("androidx.camera:camera-view:1.3.1")
            implementation("androidx.camera:camera-extensions:1.3.1")
            implementation("io.ktor:ktor-client-android:3.2.2")
            implementation("org.tensorflow:tensorflow-lite:2.12.0")
            implementation("org.tensorflow:tensorflow-lite-support:0.4.3")
            implementation("org.tensorflow:tensorflow-lite-gpu:2.13.0")
            implementation("org.tensorflow:tensorflow-lite:+")
            implementation("org.tensorflow:tensorflow-lite-gpu:+")
            implementation("org.tensorflow:tensorflow-lite-gpu-api:+")
            implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:+")
            implementation("org.tensorflow:tensorflow-lite-support:+")
            implementation("com.google.ar:core:1.41.0")
            implementation(libs.core)
// or latest version
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation("io.ktor:ktor-client-core:3.2.2")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "org.carftaura.depthgramapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.carftaura.depthgramapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

