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
            implementation(project(":core:reader"))
            implementation(project(":core:designsystem"))
            implementation(project(":data:repository"))
            implementation(project(":data:download"))
            implementation(project(":data:localimport"))
            implementation(project(":data:network"))
            implementation(project(":data:db"))
            implementation(project(":data:localimport"))
            implementation(project(":data:images"))
            implementation(project(":source:bridge"))
            implementation(project(":source:registry"))
            implementation(project(":source:hostapi"))
            implementation(project(":source:contract"))
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            implementation(libs.koin.viewmodel.compose)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(project(":feature:shelf"))
            implementation(project(":feature:explore"))
            implementation(project(":feature:search"))
            implementation(project(":feature:detail"))
            implementation(project(":feature:sources"))
            implementation(project(":feature:downloads"))
            implementation(project(":feature:settings"))
            implementation(project(":feature:reader"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
