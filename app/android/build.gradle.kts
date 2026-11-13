import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// 纯 Android application 模块（AGP 9 新 DSL 禁止 KMP android() + application 同模块，
// 见 kotl.in/gradle/agp-new-kmp；KMP 侧用 :app:shared-ui 的 android target）。
// applicationId / versionCode / versionName 为占位，打包签名随 M7 定（见架构 M7）。
android {
    namespace = "app.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "reader.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":app:shared-ui"))
    implementation(project(":data:db"))
    implementation(project(":data:repository"))
    implementation(project(":data:localimport"))
    implementation(project(":data:network"))
    implementation(project(":data:download"))
    implementation(project(":core:model"))
    implementation(project(":core:reader"))
    // A-5：LogcatAppLog / UpdateScheduler 平台触发。
    implementation(project(":core:common"))
    implementation(libs.sqldelight.android.driver)
    implementation(libs.ktor.client.core)
    implementation(libs.androidx.activity.compose)
}
