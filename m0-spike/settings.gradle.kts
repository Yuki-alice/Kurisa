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
        // Compose WebView 的 desktop KCEF 传递依赖 gluegen-rt / jogl-all 2.5.0，只在此仓库发布
        maven("https://jogamp.org/deployment/maven")
    }
}

rootProject.name = "m0-spike"
include(":shared")
