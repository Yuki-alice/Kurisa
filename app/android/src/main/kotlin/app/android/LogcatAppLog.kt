package app.android

import android.util.Log
import core.common.AppLog

/**
 * Android 日志后端（A-5 / BE-B6）。
 *
 * 脱敏约定（privacy-audit）：只写标签与消息摘要，不写完整堆栈 / 请求正文 / Cookie 值；
 * release 包由 R8 / 系统 logcat 权限控制，不在代码里输出敏感字段。
 */
class LogcatAppLog : AppLog {
    override fun d(tag: String, message: String) {
        Log.d(tag, message.take(MAX_MESSAGE_CHARS))
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message.take(MAX_MESSAGE_CHARS))
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message.take(MAX_MESSAGE_CHARS) + (throwable?.message?.let { " | $it" }.orEmpty()))
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 500
    }
}
