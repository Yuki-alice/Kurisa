import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "data.db"
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

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        binaries {
            framework {
                baseName = "DataDb"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("ReaderDatabase") {
            packageName.set("data.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            // BE-B8：databases/ 补 2–6 号 schema 快照，打开迁移校验（2→6 全链，含本轮 5.sqm）。
            // 1.sqm 是 ALTER TABLE ADD COLUMN，追加列与 .sq 定义的 ordinalPosition 不一致，
            // 且 SQLDelight 会比较 CREATE 定义文本，v1 起点无法纳入自动校验；
            // v1→v2 仍由 MigrationAndOfflineTest 的 migrateV1ToV2/rollbackV2ToV1 手工验证。
            verifyMigrations.set(true)
        }
    }
}
