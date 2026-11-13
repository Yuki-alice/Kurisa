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
            implementation(project(":data:localimport"))
            implementation(project(":data:network"))
            implementation(project(":data:download"))
            implementation(project(":core:model"))
            implementation(project(":core:reader"))
            // A-5：FileAppLog / UpdateScheduler 平台触发。
            implementation(project(":core:common"))
            // BE-C3：桌面壳构造 CookieImportHostUi 时要解析 HostUi 契约类型。
            implementation(project(":source:hostapi"))
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.ktor.client.core)
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
