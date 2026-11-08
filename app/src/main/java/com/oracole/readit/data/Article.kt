package com.oracole.readit.data

/**
 * 文章实体
 * progressOffset 存字符偏移量，不存页码，字号/翻页模式变化不影响
 */
data class Article(
    val id: Long = 0,
    val title: String,
    val content: String,
    val charCount: Int,
    val wordCount: Int,
    val progressOffset: Int = 0,
    val isFavorite: Boolean = false,
    val isFinished: Boolean = false,
    val sourceName: String? = null,
    val createdAt: Long,
    val lastReadAt: Long? = null,
    val finishedAt: Long? = null,
    /** 生成文章时用到的目标词，逗号分隔。普通导入的文章为 null */
    val targetWords: String? = null,
    /** 阅读页是否给目标词加标注（每篇独立，随时可开关） */
    val highlightTargets: Boolean = false
) {
    /** 目标词列表 */
    val targetList: List<String>
        get() = targetWords?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val isGenerated: Boolean get() = !targetWords.isNullOrBlank()
    /** 阅读进度 0f..1f */
    val progress: Float
        get() = if (charCount <= 0) 0f else (progressOffset.toFloat() / charCount).coerceIn(0f, 1f)

    val progressPercent: Int get() = (progress * 100).toInt()
}

/** 估算词数：按空白切分 */
fun countWords(text: String): Int =
    text.split(' ', '\n', '\t', '\r')
        .count { it.any(Char::isLetterOrDigit) }

/** 从正文提取标题：第一行非空，截断到 40 字 */
fun deriveTitle(text: String, fallback: String = "未命名"): String {
    val firstLine = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return fallback
    return if (firstLine.length <= 40) firstLine else firstLine.take(40) + "…"
}
