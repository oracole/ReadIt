package com.oracole.readit.data.ai

/**
 * 全文翻译的执行器
 *
 * 关键设计：
 * 1. 按段落分批（默认 8 段），每批独立请求。整篇一次发必然超 token
 * 2. 给每批明确的段落编号，返回按 paras 顺序对号入座，不依赖 AI 保持顺序
 * 3. 成功的批次进缓存，重试时跳过，不重复花钱
 * 4. 任一批失败 → 整篇失败。半篇译文没法用
 */
class ArticleTranslator(
    private val apiKey: String,
    private val model: String,
    private val cache: AiCacheRepository?,
    private val onUsage: (Usage) -> Unit
) {

    data class Result(
        val title: String,
        val content: String,
        val batches: Int
    )

    sealed interface Outcome {
        data class Ok(val result: Result) : Outcome
        data class Fail(val message: String) : Outcome
    }

    /**
     * @param paragraphs 已按段落切好的英文
     * @param mode 0 纯中文 / 1 句对照 / 2 段对照
     * @param style 文风要求的自然语言描述，空串表示默认
     * @param thinking "low"/"high"/"max"/null
     * @param batchSize 每批段数
     * @param onProgress (已完成批数, 总批数)
     */
    suspend fun run(
        paragraphs: List<String>,
        mode: Int,
        style: String,
        thinking: String?,
        batchSize: Int = 8,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): Outcome {
        if (paragraphs.isEmpty()) return Outcome.Fail("这篇文章没有内容")
        if (apiKey.isBlank()) return Outcome.Fail("还没有配置 DeepSeek 密钥")

        val total = (paragraphs.size + batchSize - 1) / batchSize
        val out = ArrayList<String>(total)
        var cnTitle: String? = null
        // 先报一次0/N，否则第一批跑完之前进度一直是 null
        // 悬浮条只会显示点击查看进度，点进翻译页也看不到任何数字
        onProgress(0, total)

        for (b in 0 until total) {
            if (shouldCancel()) return Outcome.Fail("已取消")

            val from = b * batchSize
            val to = minOf(from + batchSize - 1, paragraphs.size - 1)
            val chunk = paragraphs.subList(from, to + 1)

            // 缓存键：段落内容 + 输出形式 + 文风。任一变化都要重译
            val cacheKey = chunk.joinToString("\u0001")
            // 版本号必须带上：改了提示词后，旧缓存里的结果（比如只有纯中文）
            // 仍然会命中，用户就会以为"改了没生效"。改提示词时把 v 加一
            val cacheTab = "TRANS_ALL|v2|$mode|${style.hashCode()}"
            val cached = cache?.get(cacheKey, "batch", cacheTab)
            if (cached != null) {
                val parsed = parseTranslateBatch(cached)
                if (parsed != null) {
                    if (cnTitle == null) cnTitle = parsed.title
                    out.add(parsed.content)
                    onProgress(b + 1, total)
                    continue
                }
            }

            // max_tokens 要同时装下三样东西：
            // ① 正文，中文比英文耗 token，双语模式还要翻倍
            //   ② JSON 骨架
            // ③ 思考链，开启时也计入这个上限
            //
            // 之前 max 档只给 6000、总上限硬卡 16000，高层级必然被截断：
            // 光思考链就可能吃掉一两万，正文没地方放了
            val srcWords = chunk.sumOf { it.count { c -> c == ' ' } + 1 }
            val coef = if (mode == 0) 1.8f else 3.2f
            val thinkBudget = when (thinking) {
                null -> 0
                "low" -> 3000
                "high" -> 12000
                else -> 24000          // max
            }
            val maxTok = (srcWords * 1.6f * coef + 600f + thinkBudget)
                .toInt().coerceIn(1024, 32000)

            val r = DeepSeekClient.chat(
                apiKey = apiKey,
                systemPrompt = Prompt.translateAllSystem(mode),
                userPrompt = Prompt.translateAllUser(chunk, from + 1, paragraphs.size, style),
                maxTokens = maxTok,
                model = model,
                timeoutMs = if (thinking == null) 180_000 else 600_000,
                thinkingLevel = thinking,
                // 把模式也写进 tag，日志里能一眼看出当时选的是哪种
                tag = "TRANSLATE_ALL/模式$mode/第${b + 1}批"
            )

            when (r) {
                is AiResult.Ok -> {
                    onUsage(r.usage)
                    val parsed = parseTranslateBatch(r.content)
                        ?: return Outcome.Fail("第 ${b + 1} 批返回格式异常，请重试")
                    if (cnTitle == null) cnTitle = parsed.title
                    out.add(parsed.content)
                    cache?.put(cacheKey, "batch", cacheTab, r.content, model)
                    onProgress(b + 1, total)
                }
                is AiResult.Fail -> {
                    val msg = when (r.kind) {
                        ErrorKind.NO_KEY -> "还没有配置 DeepSeek 密钥"
                        ErrorKind.INVALID_KEY -> "密钥无效"
                        ErrorKind.INSUFFICIENT_BALANCE -> "余额不足"
                        ErrorKind.TIMEOUT -> "第 ${b + 1} 批超时，请重试"
                        ErrorKind.TRUNCATED -> "第 ${b + 1} 批被截断，可试试更短的篇幅或换输出形式"
                        else -> "第 ${b + 1} 批失败，请重试"
                    }
                    return Outcome.Fail(msg)
                }
            }
        }

        // 拼接：批次之间空一行。每批内容已自带段内换行，这里只补接缝
        return Outcome.Ok(
            Result(
                title = cnTitle?.trim()?.takeIf { it.isNotBlank() } ?: "译文",
                content = out.joinToString("\n\n") { it.trim() },
                batches = total
            )
        )
    }
}

/** 把正文按「连续两个换行」切成段落，和阅读页的切法保持一致 */
fun splitParagraphs(text: String): List<String> {
    val out = ArrayList<String>()
    val norm = text.replace("\r\n", "\n").replace('\r', '\n')
    var i = 0
    while (i < norm.length) {
        var j = i
        while (j < norm.length && !(norm[j] == '\n' && j + 1 < norm.length && norm[j + 1] == '\n')) j++
        val piece = norm.substring(i, j).trim()
        if (piece.isNotEmpty()) out.add(piece)
        i = j
        while (i < norm.length && norm[i] == '\n') i++
    }
    return out
}
