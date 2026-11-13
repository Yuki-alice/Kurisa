package app.desktop

import core.common.AppLog
import java.io.File

/**
 * 桌面日志后端（A-5 / BE-B6）：追加写 `~/.kurisa/app.log`。
 *
 * 脱敏约定（privacy-audit）：只写级别/标签/消息摘要与异常 message，
 * 不写完整堆栈、请求正文、URL 查询串或 Cookie 值——源侧日志不得携带敏感数据。
 */
class FileAppLog(private val file: File) : AppLog {
    private val lock = Any()

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val line = buildString {
            append(System.currentTimeMillis()).append(' ').append(level).append('/').append(tag).append(' ')
            append(message.take(MAX_MESSAGE_CHARS))
            throwable?.message?.let { append(" | ").append(it.take(MAX_MESSAGE_CHARS)) }
        }
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    override fun d(tag: String, message: String) = write("D", tag, message, null)
    override fun w(tag: String, message: String) = write("W", tag, message, null)
    override fun e(tag: String, message: String, throwable: Throwable?) =
        write("E", tag, message, throwable)

    private companion object {
        const val MAX_MESSAGE_CHARS = 500
    }
}
