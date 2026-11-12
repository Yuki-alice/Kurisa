plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    applyDefaultHierarchyTemplate()

    jvm("desktop")

    sourceSets {
        getByName("desktopMain").dependencies {
            implementation(project(":app:shared-ui"))
            implementation(project(":data:db"))
            implementation(project(":data:repository"))
            implementation(project(":core:reader"))
            implementation(libs.sqldelight.sqlite.driver)
            implementation(compose.desktop.currentOs)
        }
    }
}

// 窗口/托盘/快捷键/拖放随 M5 补齐（`:core:platform` 的 DesktopShell）；
// 打包签名与 helper 分发随 M6/M7（见 ADR-010）。
compose.desktop {
    application {
        mainClass = "app.desktop.MainKt"
    }
}
