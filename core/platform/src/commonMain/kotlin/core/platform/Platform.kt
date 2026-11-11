package core.platform

/**
 * 应用目录（FileKit 负责选择/保存，kotlinx-io/Okio 负责读写；
 * 根路径差异压缩到此一个接口，见架构 5.3）。
 */
interface AppDirectories {
    /** 数据库/源脚本/缓存/下载/日志的根目录（各端 actual 提供）。 */
    val appDataDir: String
}

/** 平台信息（分支逻辑一律收口到此处，禁止散落 Platform checks）。 */
interface PlatformInfo {
    val name: String // "android" | "ios" | "desktop"
    val isDebug: Boolean
}
