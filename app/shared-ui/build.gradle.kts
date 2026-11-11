import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm("desktop")

    // iOS framework 唯一出口（umbrella）：其余模块一律 implementation；
    // 显式 export() 与 SKIE 随 M5 接入。
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        binaries {
            framework {
                baseName = "SharedUI"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.navigation3.ui)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))
            implementation(project(":feature:shelf"))
            implementation(project(":feature:explore"))
            implementation(project(":feature:search"))
            implementation(project(":feature:detail"))
            implementation(project(":feature:sources"))
            implementation(project(":feature:downloads"))
            implementation(project(":feature:settings"))
            implementation(project(":feature:reader"))
        }
    }
}
