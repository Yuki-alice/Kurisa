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
            // SHA-1/512（korlibs-crypto，与 :source:registry 同库同版本三端一致）
            implementation(libs.korlibs.crypto)
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
            // 仓库门禁需真实安装路径（RulesHeader + SourceInstaller 语义），仅 desktopTest 引用。
            implementation(project(":source:registry"))
        }
    }
}

// 本地真实源 E2E：refs 目录与域名白名单由运行者通过 -D 提供，缺省时测试自动跳过。
// 代码库内不含任何站点 URL 或第三方源脚本（C1 / GPL 隔离）。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf("kurisa.refsDir", "kurisa.e2eDomains", "kurisa.e2eReport", "kurisa.sourcesRepoDir").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // 两仓 schema 一致性（B-6）：默认取主仓契约，可用 -Dkurisa.contractSchema 覆盖。
    systemProperty(
        "kurisa.contractSchema",
        System.getProperty("kurisa.contractSchema")
            ?: rootProject.file("docs/contract/index-schema.json").absolutePath,
    )
}
