import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "source.contract"
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

    // iOS framework 导出：正式工程只有 :app:shared-ui 配 framework；
    // M1 在 :source:contract 保留最小 binary 以验证三端链接。
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        binaries {
            framework {
                baseName = "SourceContract"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // 仅测试域：quickjs-kt + ksoup 只用于“契约可执行”验证，
            // 发布物保持纯 Kotlin 零引擎依赖。
            implementation(libs.quickjs.kt)
            implementation(libs.ksoup)
        }
    }
}
