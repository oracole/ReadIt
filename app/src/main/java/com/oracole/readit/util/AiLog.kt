package com.oracole.readit.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 请求日志
 *
 * 目的很简单：出问题时不用猜。每次请求把用途 / system / user / 返回记下来
 * 设置里能直接看，也能复制出来发给别人
 *
 * 存文件而不是数据库：纯文本、好导出、不用建表、不用迁移
 * 环形保留最后 [MAX_ENTRIES] 条，单条超长会截断，避免日志本身占满存储
 */
object AiLog {

    private const val MAX_ENTRIES = 200
    private const val MAX_FIELD = 1200       // 每个字段最多保留这么多字符
    private const val FILE_NAME = "ai_log.txt"

    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    fun init(ctx: Context) {
        file = File(ctx.applicationContext.filesDir, FILE_NAME)
    }

    fun path(): String = file?.absolutePath ?: ""

    /** 记一条。tag 是用途，比如 "SEARCH_ASK" / "TRANSLATE_ALL" */
    fun record(tag: String, system: String, user: String, result: String) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                val entry = buildString {
                    append("━━━ ").append(fmt.format(Date())).append("  ").append(tag).append('\n')
                    append("[system] ").append(oneLine(system)).append('\n')
                    append("[user]   ").append(oneLine(user)).append('\n')
                    append("[result] ").append(oneLine(result)).append("\n\n")
                }
                f.appendText(entry)
                trimIfNeeded(f)
            }
        }
    }

    /** 读全部。最新的在最后。 */
    fun readAll(): String {
        val f = file ?: return "日志未初始化"
        return runCatching {
            if (!f.exists()) "暂无日志" else f.readText().ifBlank { "暂无日志" }
        }.getOrDefault("读取失败")
    }

    fun clear() {
        val f = file ?: return
        synchronized(lock) { runCatching { f.writeText("") } }
    }

    private fun oneLine(s: String): String {
        val flat = s.replace("\r\n", "\\n").replace('\n', ' ').trim()
        return if (flat.length > MAX_FIELD) flat.take(MAX_FIELD) + " …(截断)" else flat
    }

    /** 超过上限就砍掉最旧的一半，避免每次写都全量重排 */
    private fun trimIfNeeded(f: File) {
        val text = f.readText()
        val count = text.count { it == '━' } / 3
        if (count <= MAX_ENTRIES) return
        val parts = text.split("━━━ ")
        // parts[0] 是空串，其余是各条
        val keep = parts.drop(1).takeLast(MAX_ENTRIES / 2)
        f.writeText(keep.joinToString("") { "━━━ $it" })
    }
}
