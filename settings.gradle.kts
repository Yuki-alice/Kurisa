pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "reader"

// core ①（纯 Kotlin）：model 已有（M1/M2），其余为初始化骨架
include(":source:contract")
include(":core:model")
include(":core:common")
include(":core:reader")
include(":core:designsystem")
include(":core:platform")
// data ②：db/network/repository 已有（M2），其余为初始化骨架
include(":data:db")
include(":data:network")
include(":data:repository")
include(":data:download")
include(":data:localimport")
include(":data:cache")
// source ③：contract/registry 已有，hostapi/bridge 为初始化骨架
include(":source:registry")
include(":source:hostapi")
include(":source:bridge")
// feature ④：8 个目的地（初始化骨架，页面实现随 M3–M5）
include(":feature:shelf")
include(":feature:explore")
include(":feature:search")
include(":feature:detail")
include(":feature:sources")
include(":feature:downloads")
include(":feature:settings")
include(":feature:reader")
// app ⑤：平台壳（shared-ui 聚合路由；iOS Xcode 工程见 app/ios/README）
include(":app:shared-ui")
include(":app:android")
include(":app:desktop")
