import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "spike.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            // CMP core — explicit coordinates, never the compose.material3 accessor
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.navigation3.ui)

            // JS runtime
            implementation(libs.quickjs.kt)
            implementation(libs.quickjs.converter)

            // HTML / XML / encoding
            implementation(libs.ksoup)
            implementation(libs.xmlutil.core)
            implementation(libs.korlibs.encoding)

            // network / serialization
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)

            // persistence
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)

            // images
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.sketch4.compose)
            implementation(libs.zoomimage.compose)
            implementation(libs.zoomimage.compose.coil3)
            implementation(libs.zoomimage.compose.sketch4)

            // webview / pdf
            implementation(libs.compose.webview)
            implementation(libs.pdfium)

            // DI
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }

        // 自定义 target 名 (jvm("desktop")) 不生成 DSL 访问器，需 getByName
        getByName("desktopMain").dependencies {
            implementation(libs.ktor.client.cio)
            implementation(libs.sqldelight.sqlite.driver)
            // Skiko 运行时（含 linux-x64 原生库），离屏渲染与桌面窗口都需要
            implementation(compose.desktop.currentOs)
        }
    }
}

// gluegen-rt / jogl-all 2.5.0 只发布在 jogamp.org（Maven Central 与国内镜像均 404），
// 容器 / CI 常无法访问该站点，会让整个 desktopRuntimeClasspath 解析失败。
// 此 configuration 排除 KCEF 相关依赖，供不涉及桌面 WebView 的运行任务使用。
val offlineDesktopRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.getByName("desktopRuntimeClasspath"))
    exclude(group = "org.jogamp.gluegen")
    exclude(group = "org.jogamp.jogl")
    exclude(group = "io.github.kevinnzou")
}

tasks.register<JavaExec>("runDesktopSpike") {
    group = "verification"
    description = "M0 behaviour spike: JS promise chain, memory limit, interruption"
    dependsOn("compileKotlinDesktop")
    classpath = files(
        configurations.getByName("offlineDesktopRuntime"),
        layout.buildDirectory.dir("classes/kotlin/desktop/main"),
    )
    mainClass.set("spike.MainKt")
}

// M0 渲染验证：离屏渲染 SpikeApp -> PNG（headless 可跑，不需要窗口系统）
tasks.register<JavaExec>("screenshotSpike") {
    group = "verification"
    description = "M0 render check: offscreen-render SpikeApp to PNG"
    dependsOn("compileKotlinDesktop")
    classpath = files(
        configurations.getByName("offlineDesktopRuntime"),
        layout.buildDirectory.dir("classes/kotlin/desktop/main"),
    )
    mainClass.set("spike.ScreenshotKt")
    // 容器 / CI 里 DISPLAY 可能指向不存在的 X server，强制 AWT headless
    jvmArgs("-Djava.awt.headless=true")
    environment("DISPLAY", "")
    args(layout.buildDirectory.file("screenshots/spike-desktop.png").get().asFile.absolutePath)
}

// 真实 Venera 源加载：./gradlew :shared:sourceSpike --args="<source.js> [keyword]"
tasks.register<JavaExec>("sourceSpike") {
    group = "verification"
    description = "Load a real Venera .js source through the minimal Host API"
    dependsOn("compileKotlinDesktop")
    classpath = files(
        configurations.getByName("offlineDesktopRuntime"),
        layout.buildDirectory.dir("classes/kotlin/desktop/main"),
    )
    mainClass.set("spike.SourceMainKt")
}

// 桌面 WebView 替代方案验证：./gradlew :shared:accountSpike --args="<source.js>"
// 对照「空 CookieJar」与「注入 Cookie」两种情况下源的登录态判定。
tasks.register<JavaExec>("accountSpike") {
    group = "verification"
    description = "Verify that loginWithWebview can be replaced by cookie injection"
    dependsOn("compileKotlinDesktop")
    classpath = files(
        configurations.getByName("offlineDesktopRuntime"),
        layout.buildDirectory.dir("classes/kotlin/desktop/main"),
    )
    mainClass.set("spike.AccountMainKt")
}
