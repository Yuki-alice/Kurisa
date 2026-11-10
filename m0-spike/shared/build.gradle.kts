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
        }
    }
}

tasks.register<JavaExec>("runDesktopSpike") {
    group = "verification"
    description = "M0 behaviour spike: JS promise chain, memory limit, interruption"
    dependsOn("compileKotlinDesktop")
    classpath = files(
        configurations.getByName("desktopRuntimeClasspath"),
        layout.buildDirectory.dir("classes/kotlin/desktop/main"),
    )
    mainClass.set("spike.MainKt")
}
