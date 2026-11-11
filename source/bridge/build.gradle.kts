import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "source.bridge"
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

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:reader"))
            implementation(project(":source:contract"))
            implementation(project(":source:hostapi"))
            implementation(project(":data:network"))
            implementation(project(":data:images"))
            implementation(libs.quickjs.kt)
            implementation(libs.quickjs.converter)
            implementation(libs.ksoup)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

// 本地真实源 E2E：refs 目录与域名白名单由运行者通过 -D 提供，缺省时测试自动跳过。
// 代码库内不含任何站点 URL 或第三方源脚本（C1 / GPL 隔离）。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf("kurisa.refsDir", "kurisa.e2eDomains", "kurisa.e2eReport").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
