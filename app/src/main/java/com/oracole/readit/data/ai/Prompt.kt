package com.oracole.readit.data.ai

/**
 * 提示词
 * system 段必须逐字不变，以命中 DeepSeek 前缀缓存
 */
object Prompt {

    /** 四项共用的写作规则，作为固定前缀 */
    private const val RULES = """你是英汉词典的编辑，为英文原版书读者撰写词条。输出 json。

写作规则：
1. 不出现主语，不称呼读者，不使用第一人称。
2. 不复述原句，不解释自己在做什么，不加寒暄、过渡、总结、客套。
3. 不使用 Markdown 标记。中文用全角标点，英文词语保留原形。
4. 无法确定时给出最可能的内容，并在 confidence 填 low，不要编造。
5. confidence 只能是 high 或 low。
6. pos 字段只填词性缩写，不写完整单词。
   缩写按通行词典写法，如 n. / v. / adj. / adv. / phr.，一律小写带句点。"""

    const val SENSE_SYSTEM = RULES + """

任务：给出这个词在这句话里的意思。

字段长度：sense_here ≤ 16 字，note ≤ 40 字，每项 common ≤ 12 字。
输出 json（严格遵守，不要增删字段）：
{
  "base": "在这个语境下这个词的原形",
  "sense_here": "在这句话里的意思，短语",
  "common": ["常用义1", "常用义2"],
  "pos": "词性缩写，如 vt. / n. / adj.",
  "note": "语感或用法提示，可为空字符串",
  "dict_gloss": "词典式释义，见下",
  "confidence": "high 或 low"
}

base 的写法：填「在这句话里，这个词实际属于哪个词」的原形。
它和形态还原不是一回事 —— found 在这句里是「建立」就填 found，
是「找到」才填 find。rose 当玫瑰用就填 rose，当 rise 的过去式才填 rise。
专有名词与人名保留原有大小写，不要转小写。
这个词本身就是原形时，base 与单词相同。

dict_gloss 的写法：像词典条目那样给这个词的通用释义，一行一个词性，
格式「词性缩写. 释义」，同一词性的多个义项用分号分隔，总长 ≤ 40 字。
例：adj. 同时发生的；同时存在的
它与 sense_here 不同 —— sense_here 是「在这一句里」的意思，dict_gloss 是这个词本身的通用义。"""

    /**
     * 词组（多词选中）专用。与 SENSE_SYSTEM 的关键差别：
     * 要求把整个词组当作一个单位来解释，而不是只给核心词的意思
     * 单独一个常量，不污染 SENSE_SYSTEM 的前缀缓存
     */
    const val SENSE_PHRASE_SYSTEM = RULES + """
任务：给出这个英文词组在这句话里的意思。

注意：输入可能由两个或多个单词组成，必须把它当作一个整体来解释。
不要只解释其中某个词，也不要把它拆成几个词分别解释。
若它本身不是固定搭配，就说明这几个词连在一起在这里表达什么。

sense_here：整个词组在这句话里的意思，≤ 16 字。
dict_gloss：这个词组本身的通用义，像词典条目，格式「词性缩写. 释义」，≤ 40 字。
            若它不是固定搭配，填这一串词的字面组合义。

输出 json（严格遵守，不要增删字段）：
{
  "base": "这个词组本身，保留原有大小写",
  "sense_here": "整个词组在这句话里的意思",
  "common": ["该词组的其他常见含义1", "该词组的其他常见含义2"],
  "pos": "整体词性，如 n. / adj. / phr.",
  "note": "用法或搭配提示，可为空字符串",
  "dict_gloss": "这个词组的词典式释义",
  "confidence": "high 或 low"
}"""

    const val ROOT_SYSTEM = RULES + """

任务：拆解这个词的词根词缀，帮助记忆。若为单纯词无词根可拆，说明其词源。

字段长度：origin ≤ 60 字，memo ≤ 24 字。
输出 json（严格遵守）：
{
  "parts": [
    {"part": "ab-", "meaning": "离开，脱离"},
    {"part": "-andon", "meaning": "放弃"}
  ],
  "origin": "词源说明，可为空字符串",
  "memo": "一句话记忆钩子，可为空字符串",
  "confidence": "high 或 low"
}"""

    /**
     * 词形：这个词有哪几种词性、各自的原形是什么
     *
     * 不含释义，释义由词典和语境义负责，这里只解决
     * -ed 结尾的词同时是形容词和动词过去式这类困惑
     */
    const val FORM_SYSTEM = RULES + """

任务：列出这个词的各个词性，以及每种词性对应的原形。

只看形态，不要给释义，不要解释用法。
同一个词性只出现一次。按 adj. / adv. / n. / v. / 其他 的顺序排列。

字段长度：原形 ≤ 20 字符，说明 ≤ 12 字。
输出 json（严格遵守）：
{
  "forms": [
    {"pos": "adj.", "base": "tired", "note": ""},
    {"pos": "v.", "base": "tire", "note": "过去式 / 过去分词"}
  ],
  "confidence": "high 或 low"
}"""

    const val COLLOCATION_SYSTEM = RULES + """

任务：给出这个词最常用的搭配或固定表达，只收真正高频的。

输出 json（严格遵守）：
{
  "items": [
    {"phrase": "abandon hope", "meaning": "放弃希望"},
    {"phrase": "abandon ship", "meaning": "弃船"}
  ],
  "confidence": "high 或 low"
}"""

    /**
     * 派生词。只列同根的其他词形，不造句、不解释
     *
     * 版式有明确要求：
     *   不同派生词各占一行
     *   同一个派生词的多个词性不换行，用空格隔开，不用分号
     */
    const val DERIVE_SYSTEM = RULES + """

任务：列出这个词的常见派生词（同根的其他词形），只收真正常用的。

规则：
1. 一个派生词一行，不要合并成一句。
2. 同一个派生词有多种词性时，词性之间用空格分隔，不要用分号，不要换行。
3. 不造句，不解释用法，不列释义相近的纯屈折变化（如 -s / -ed / -ing）。

字段长度：word ≤ 24 字符，meaning ≤ 10 字。
输出 json（严格遵守）：
{
  "items": [
    {"word": "creation", "pos": "n.", "meaning": "创造"},
    {"word": "creative", "pos": "adj. n.", "meaning": "有创造力的"},
    {"word": "creativity", "pos": "n.", "meaning": "创造力"}
  ],
  "confidence": "high 或 low"
}"""

    const val EXAMPLE_SYSTEM = RULES + """

任务：为这个词造 2 个例句，难度接近母语者日常阅读。句子必须地道、短、不含生僻词。

输出 json（严格遵守）：
{
  "items": [
    {"en": "英文例句", "zh": "中文翻译"}
  ],
  "confidence": "high 或 low"
}"""

    const val PHONETIC_SYSTEM = RULES + """
任务：给出这个词的英式和美式音标。

用国际音标，不带斜杠，不带重音以外的任何标记。
英式以 RP 为准，美式以 General American 为准；两者相同时填相同内容。

输出 json（严格遵守）：
{
  "uk": "英式音标",
  "us": "美式音标",
  "confidence": "high 或 low"
}"""

    /**
     * 用生词生成一篇阅读文章
     * 注意：system 段固定，只有 user 段变化，这样能命中前缀缓存
     */
    const val GENERATE_SYSTEM = """你是英语教学材料编辑，为语言学习者撰写阅读文章。输出 json。

写作规则：
1. 文章必须自然、连贯，有开头、发展、结尾，不是生词堆砌。目标词要出现在合理的语境里。
2. 优先使用给定的目标词；确实无法自然融入的，可以放弃，但最多放弃 2 个。
3. 难度必须匹配要求：句式复杂度、词汇范围都要控制住。给定 CEFR 等级时按该等级；给定自然语言描述时，以描述为准。
4. 不使用 Markdown 标记，不要标题编号，正文直接开始。
5. 文章末尾不要附词汇表、不要加练习题、不要写"本文使用了以下单词"之类的话。
6. 目标词按原形或正常变形使用均可，不必强行保持原形。

字段：
- title：用英文，不超过 8 个单词，不要引号，不要用中文
- content：正文，段落之间用 \n\n 分隔
- used：实际用到的目标词
- skipped：没能用上的目标词

输出 json（严格遵守）：
{
  "title": "文章标题",
  "content": "正文",
  "used": ["..."],
  "skipped": ["..."]
}"""

    /**
     * 整句翻译。上下文给整段，不是只给这一句，
     * 代词指代、一词多义都靠上下文才能译准
     * system 段固定，可命中前缀缓存
     */
    const val TRANSLATE_SYSTEM = """你是英汉翻译。输出 json。

规则：
1. 只翻译「目标句」，不要翻译上下文。上下文只用来理解指代和语气。
2. 译文要像中文原生的句子，不是逐字直译；该调整语序就调整。
3. 保留原文的人名、地名、术语原文，可在括号里给中文。
4. 不加解释、不加注音、不写"这句话的意思是"。
5. 译文长度尽量接近原文，不要扩写成两倍。

输出 json（严格遵守）：
{
  "translation": "中文译文",
  "note": "如有必要的理解提示，≤ 30 字；否则空字符串",
  "confidence": "high 或 low"
}"""

    /**
     * 句子解析：解释为什么这样译，而不是再译一遍
     * 只在整句卡片里按需展开，不进翻译模式
     */
    const val EXPLAIN_SYSTEM = RULES + """

任务：解释这句英文的理解难点，帮助读者读懂。

只写读者容易卡住的地方，讲清为什么。常规句法不必展开。
不逐词翻译，不复述译文，不评价句子写得好坏。

字段长度：point ≤ 40 字，detail ≤ 120 字。
输出 json（严格遵守）：
{
  "point": "这句话的难点是什么，一句话",
  "detail": "展开说明：为什么这样理解、有没有易混点",
  "confidence": "high 或 low"
}"""

    /**
     * 搜索页的自由提问
     *
     * 分两种情形，让模型自己判断：
     *   - 问的是一个词/短语 → 输出词典式结构（词头、音标、义项数组）
     *   - 问的是别的（用法区别、语法、翻译）→ 输出普通回答
     *
     * 为什么用结构而不是让它在字符串里塞换行：
     * 模型会把 \n 当字面量照抄出来，界面上就显示成 "\n1. \n2."
     * 改成数组后，排版由我们做，永远不会出现这种字符
     */
    const val SEARCH_ASK_SYSTEM = """你是英语学习助手，回答读者关于英语的问题。输出 json。

先判断问题在问什么：
- 如果问的是一个单词或短语（「XX 什么意思」「XX 怎么用」「XX 和 YY 的区别」），
  按词典条目回答，填 kind = "word"。
- 其他情况（语法、翻译一段话、写作建议），填 kind = "general"，把回答放进 text。

要求：
1. 义项用数组，一个义项一个元素，不要把多个义项挤进一句话。
2. 释义简短，≤ 20 字；需要举例时放进 example。
3. 不确定的内容不要编，confidence 填 low。
4. 不写客套话。
5. pos 只填词性缩写，不写完整单词。缩写按通行词典写法，一律小写带句点。

输出 json（严格遵守）：
{
  "kind": "word 或 general",
  "headword": "词头（kind=word 时填）",
  "phonetic": "音标，没有就空字符串",
  "senses": [
    { "pos": "n.", "meaning": "释义", "example": "例句，可空" }
  ],
  "note": "用法提示或易混点，可空",
  "text": "kind=general 时的回答，段落之间用空行分隔",
  "confidence": "high 或 low"
}"""

    /**
     * 详情页问 AI：针对一个已收藏的词追问
     * 不是对话，每次问答独立，用户已经知道词义，只想问具体问题
     */
    const val ASK_SYSTEM = RULES + """

任务：回答读者关于这个单词的提问。

回答要具体、克制，像词典编辑答疑，不像客服。
答不上来就说不知道，不要兜圈子。
字段长度：answer ≤ 200 字。

输出 json（严格遵守）：
{
  "answer": "回答正文",
  "confidence": "high 或 low"
}"""

    /**
     * 全文翻译。输出形式决定排版，三种模式各自写死格式
     *
     * 必须显式要求句子边界 1:1：LLM 默认会为了通顺而合并/拆分句子
     * 一旦发生，句对照就整体错位。这是翻译类任务最常见的失效点
     */
    /**
     * 全文翻译的 system 段
     *
     * 关键：让模型直接输出排好版的纯文本，不做句级数据结构
     * 排版靠给一个带换行的完整示例，而不是靠规则描述，
     * 规则描述它理解不了，示例它照抄得到
     */
    fun translateAllSystem(mode: Int): String = buildString {
        append("你是英汉对照翻译。输出 json。\n\n")
        // 开头就定性：模型默认把翻译理解成只输出译文
        // 选了句对照/段对照却只给中文，就是缺了这一句
        if (mode == 0) {
            append("本次任务：只输出中文译文，不要保留英文。\n\n")
        } else {
            append("本次任务：输出英文原文和中文译文对照。\n")
            append("每句英文都必须原样保留，紧跟它的中文译文。绝不能只输出中文。\n\n")
        }
        append("规则：\n")
        append("1. 保持原文的内容完整，不增不减。\n")
        append("2. 中文要像中文母语者写的，不是逐字直译；该调整语序就调整。\n")
        append("3. 人名、地名、术语保留原文，可在括号里给中文。\n")
        append("4. 不加解释、不加注音、不写\"以下是翻译\"之类的话。\n")
        append("5. content 里的换行必须写成 JSON 的 \\n 转义，不要压平成一整段。\n\n")
        append("输出 json（严格遵守，只有这两个字段）：\n")
        append("{\n  \"title\": \"中文标题（仅第一批需要，其余批次填空字符串）\",\n")
        append("  \"content\": \"译好的正文\"\n}\n\n")
        append("下面是 ").append(modeName(mode)).append(" 的示例，注意 content 里换行的位置：\n\n")
        append(exampleJson(mode))
    }

    private fun modeName(mode: Int) = when (mode) {
        0 -> "纯中文"
        1 -> "句对照"
        else -> "段对照"
    }

    /** 带真换行注释的示例 —— 模型照抄这个结构 */
    private fun exampleJson(mode: Int): String = when (mode) {
        0 -> """
{
  "title": "街头摄影",
  "content": "街头摄影就是捕捉决定性瞬间。它无关技术上的完美。\n\n决定性瞬间不是一种风格。"
}"""
        1 -> """
{
  "title": "街头摄影",
  "content": "Street photography is to capture the decisive moment.\n街头摄影就是捕捉决定性瞬间。\nIt is not about technical perfection.\n它无关技术上的完美。\n\nThe decisive moment is not a style.\n决定性瞬间不是一种风格。"
}"""
        else -> """
{
  "title": "街头摄影",
  "content": "Street photography is to capture the decisive moment. It is not about technical perfection.\n街头摄影就是捕捉决定性瞬间。它无关技术上的完美。\n\nThe decisive moment is not a style.\n决定性瞬间不是一种风格。"
}"""
    }

    /**
     * @param from 本批起始段号（1 起）
     * @param total 全文共多少段
     * @param style 文风的自然语言描述
     */
    fun translateAllUser(
        paragraphs: List<String>,
        from: Int,
        total: Int,
        style: String
    ): String = buildString {
        append("本次处理第 ").append(from).append("–").append(from + paragraphs.size - 1)
        append(" 段（全文共 ").append(total).append(" 段）\n")
        if (style.isNotBlank()) append("文风要求：").append(style).append('\n')
        append('\n')
        paragraphs.forEachIndexed { i, p ->
            append('[').append(from + i).append("] ").append(p).append('\n')
        }
    }

    /**
     * @param level 难度。CEFR 档位用 "B1"，自定义用自然语言描述
     * @param length 篇幅词数；0 表示由 customLength 决定
     * @param customLength 自定义篇幅描述，为空则忽略
     */
    fun generateUser(
        words: List<String>,
        level: String,
        length: Int,
        customLevel: String,
        customLength: String,
        requirement: String,
        topic: String,
        blend: Int = 70
    ): String = buildString {
        append("目标词（").append(words.size).append(" 个）：")
        append(words.joinToString(", ")).append('\n')
        if (customLevel.isNotBlank()) {
            append("难度：").append(customLevel.trim()).append('\n')
        } else {
            append("难度：").append(level).append('\n')
        }
        if (customLength.isNotBlank()) {
            append("篇幅：").append(customLength.trim()).append('\n')
        } else {
            append("篇幅：约 ").append(length).append(" 词\n")
        }
        append("词汇融合度：").append(blend).append("%\n")
        append("  （100% = 必须用上全部目标词，宁可牺牲自然度；")
        append("0% = 以文章自然为先，可放弃较多目标词。当前要求 ")
        append(blend).append("% 左右的目标词出现在文章中。）\n")
        if (topic.isNotBlank()) append("主题：").append(topic.trim()).append('\n')
        if (requirement.isNotBlank()) append("额外要求：").append(requirement.trim())
    }

    /**
     * 详情页的自由提问
     *
     * 和 askUser 的区别：这里把当前这个词和它的语境显式写在最前面
     * 用户问这个词是什么意思时，模型得知道"这个词"是哪个，
     * 光把问题本身发过去，它只能反问请提供具体单词
     */
    fun detailAskUser(
        word: String,
        translation: String,
        context: String,
        question: String
    ): String = buildString {
        append("当前正在看的词：").append(word).append('\n')
        if (translation.isNotBlank()) {
            append("它的释义：").append(translation.replace("\n", " / ")).append('\n')
        }
        if (context.isNotBlank()) {
            append("它出现的句子：").append(context).append('\n')
        }
        append("用户的问题：").append(question).append('\n')
        append('\n')
        append("注意：问题里说的「这个词」「它」都指上面那个词，直接回答，不要反问。")
    }

    fun askUser(word: String, translation: String, question: String): String = buildString {
        append("单词：").append(word).append('\n')
        if (translation.isNotBlank()) append("释义：").append(translation.replace("\n", " / ")).append('\n')
        append("提问：").append(question)
    }

    fun explainUser(sentence: String, translation: String): String = buildString {
        append("原句：").append(sentence).append('\n')
        if (translation.isNotBlank()) append("已有译文：").append(translation)
    }

    fun translateUser(target: String, context: String): String = buildString {
        append("目标句：").append(target).append('\n')
        if (context.isNotBlank() && context != target) {
            append("上下文（仅供理解，不要翻译）：").append(context)
        }
    }

    fun phraseUser(phrase: String, sentence: String): String = buildString {
        append("词组：").append(phrase).append('\n')
        append("所在句：").append(sentence)
    }

    fun baseUser(word: String, sentence: String): String = buildString {
        append("单词：").append(word).append('\n')
        append("所在句：").append(sentence)
    }

    /** 词组 + 词典候选义（通常为「无」） */
    fun phraseSenseUser(phrase: String, sentence: String, dictGloss: String): String = buildString {
        append(phraseUser(phrase, sentence)).append('\n')
        append("词典候选义：").append(dictGloss)
    }

    fun senseUser(word: String, sentence: String, dictGloss: String): String = buildString {
        append(baseUser(word, sentence)).append('\n')
        append("词典候选义：").append(dictGloss)
    }
}

// ---------------- 解析 ----------------

/**
 * 读字符串字段，把 JSON null 和字面量 "null" 都当成空
 *
 * 为什么需要：Android 自带的 org.json 里，optString(key, fallback)
 * 遇到显式 null 会返回字符串 "null"，不走 fallback
 * 模型把空字段写成 "base": null 时，下游会拿到一个叫 null 的词
 *
 * 注：Maven 上那份 org.json 实现是走 fallback 的，本机测不出来
 * 只有真机上是这样，所以这里做防御而不是依赖实测
 */
internal fun str(o: org.json.JSONObject, key: String): String {
    if (o.isNull(key)) return ""
    val v = o.optString(key, "")
    return if (v == "null") "" else v
}

/**
 * 词性兜底：模型偶尔会把整个单词当词性返回（例如把 channelrhodopsin-2 填进 pos）
 * 界面用固定宽度渲染那一列，长内容会一字一行竖排，排版直接崩
 *
 * 这里只做一道长度闸门，不维护映射表，词性写法太多列不全
 * 提示词里已经要求用缩写，走到这里还不合规的一律丢弃
 *
 * 阈值 8：最长常见词性缩写 interj. / excl. 都是 7 个字符
 */
internal fun sanitizePos(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.length > 8) return ""
    if (t.any { it.isWhitespace() }) return ""
    return t
}

/**
 * 模型经常把换行写成字面的反斜杠+n（双重转义），JSON 解析后仍是两个字符
 * 这里统一还原成真换行。所有多行文本字段都要过一遍
 */
internal fun unescapeNewlines(s: String): String =
    if (s.contains("\\n")) s.replace("\\n", "\n") else s

/** 去掉模型偶尔套上的 ``` 包裹 */
internal fun stripFence(s: String): String {
    var t = s.trim()
    if (t.startsWith("```")) {
        t = t.substringAfter('\n', t)
        if (t.endsWith("```")) t = t.dropLast(3)
    }
    return t
}

private fun arr(json: org.json.JSONObject, key: String): List<org.json.JSONObject> {
    val a = json.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<org.json.JSONObject>(a.length())
    for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
    return out
}

data class SenseResult(
    val base: String, val senseHere: String, val common: List<String>,
    val pos: String, val note: String,
    /** 词典式通用释义，供未收录词收藏时使用 */
    val dictGloss: String = "",
    val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

data class RootPart(val part: String, val meaning: String)
data class RootResult(
    val parts: List<RootPart>, val origin: String, val memo: String, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

/** 一种词性 + 它的原形 */
data class FormItem(val pos: String, val base: String, val note: String)
data class FormResult(
    val forms: List<FormItem>, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

fun parseForms(raw: String): FormResult? = runCatching {
    val o = jsonObject(raw)
    FormResult(
        forms = arr(o, "forms").map {
            FormItem(
                pos = sanitizePos(it.optString("pos", "")),
                base = unescapeNewlines(it.optString("base", "").trim()),
                note = unescapeNewlines(it.optString("note", "").trim())
            )
        }.filter { it.base.isNotBlank() },
        confidence = o.optString("confidence", "high")
    )
}.getOrNull()

data class PhraseItem(val phrase: String, val meaning: String)
data class CollocationResult(
    val items: List<PhraseItem>, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

/** 一条派生词：词形 + 词性 + 简短释义 */
data class DerivationItem(val word: String, val pos: String, val meaning: String)

data class DerivationResult(
    val items: List<DerivationItem>, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

fun parseDerivations(raw: String): DerivationResult? = runCatching {
    val o = jsonObject(raw)
    DerivationResult(
        items = arr(o, "items").map {
            DerivationItem(
                word = unescapeNewlines(it.optString("word", "").trim()),
                pos = unescapeNewlines(it.optString("pos", "").trim()),
                meaning = unescapeNewlines(it.optString("meaning", "").trim())
            )
        }.filter { it.word.isNotBlank() },
        confidence = o.optString("confidence", "high")
    )
}.getOrNull()

data class PhoneticResult(
    val uk: String, val us: String, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

data class ExampleItem(val en: String, val zh: String)
data class ExampleResult(
    val items: List<ExampleItem>, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

fun parseSense(raw: String): SenseResult? = runCatching {
    // 必须走 jsonObject()，不要自己 new，
    // 这是唯一会写进收藏的解析器，null 清洗漏在这里等于没做
    val o = jsonObject(raw)
    val common = ArrayList<String>()
    o.optJSONArray("common")?.let { a ->
        for (i in 0 until a.length()) {
            val v = a.optString(i, "")
            if (v.isNotBlank() && v != "null") common.add(v)
        }
    }
    SenseResult(
        base = str(o, "base"),
        senseHere = str(o, "sense_here"),
        common = common,
        pos = sanitizePos(str(o, "pos")),
        note = unescapeNewlines(str(o, "note").trim()),
        dictGloss = str(o, "dict_gloss"),
        confidence = o.optString("confidence", "high")
    )
}.getOrNull()

fun parseRoot(raw: String): RootResult? = runCatching {
    val o = jsonObject(raw)
    RootResult(
        parts = arr(o, "parts").map {
            RootPart(
                unescapeNewlines(it.optString("part", "").trim()),
                unescapeNewlines(it.optString("meaning", "").trim())
            )
        },
        origin = unescapeNewlines(o.optString("origin", "").trim()),
        memo = unescapeNewlines(o.optString("memo", "").trim()),
        confidence = o.optString("confidence", "high")
    )
}.getOrNull()

fun parseCollocations(raw: String): CollocationResult? = runCatching {
    val o = jsonObject(raw)
    CollocationResult(
        items = arr(o, "items").map {
            PhraseItem(
                unescapeNewlines(it.optString("phrase", "").trim()),
                unescapeNewlines(it.optString("meaning", "").trim())
            )
        }.filter { it.phrase.isNotBlank() },
        confidence = o.optString("confidence", "high")
    )
}.getOrNull()

/** 一批的翻译结果：正文已经是排好版的纯文本，含真换行 */
data class TranslateBatch(
    val title: String?,
    val content: String
)

fun parseTranslateBatch(raw: String): TranslateBatch? = runCatching {
    val o = jsonObject(raw)
    val t = o.optString("title", "").trim().trim('"', '《', '》')
    val c = unescapeNewlines(o.optString("content", "").trim())
    if (c.isBlank()) null else TranslateBatch(t.ifBlank { null }, c)
}.getOrNull()

/** 搜索页的回答。word 型是词典条目，general 型是普通段落。 */
data class SearchAnswer(
    val isWord: Boolean,
    val headword: String,
    val phonetic: String,
    val senses: List<SenseLine>,
    val note: String,
    val text: String,
    val confidence: String
) {
    val low: Boolean get() = confidence.equals("low", ignoreCase = true)
}

data class SenseLine(val pos: String, val meaning: String, val example: String)

fun parseSearchAnswer(raw: String): SearchAnswer? = runCatching {
    val o = jsonObject(raw)
    val kind = o.optString("kind", "general")
    val senses = arr(o, "senses").map {
        SenseLine(
            pos = sanitizePos(it.optString("pos", "")),
            meaning = unescapeNewlines(it.optString("meaning", "").trim()),
            example = unescapeNewlines(it.optString("example", "").trim())
        )
    }.filter { it.meaning.isNotBlank() }
    val text = unescapeNewlines(o.optString("text", "").trim())
    if (senses.isEmpty() && text.isBlank()) null
    else SearchAnswer(
        isWord = kind.equals("word", true) && senses.isNotEmpty(),
        headword = o.optString("headword", "").trim(),
        phonetic = o.optString("phonetic", "").trim().trim('/'),
        senses = senses,
        note = unescapeNewlines(o.optString("note", "").trim()),
        text = text,
        confidence = o.optString("confidence", "high")
    )
}.getOrNull()

data class AskResult(val answer: String, val confidence: String) {
    val low: Boolean get() = confidence.equals("low", ignoreCase = true)
}

fun parseAsk(raw: String): AskResult? = runCatching {
    val o = jsonObject(raw)
    val a = unescapeNewlines(o.optString("answer", "").trim())
    if (a.isBlank()) null else AskResult(a, o.optString("confidence", "high"))
}.getOrNull()

data class ExplainResult(
    val point: String, val detail: String, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

fun parseExplain(raw: String): ExplainResult? = runCatching {
    val o = jsonObject(raw)
    val point = unescapeNewlines(o.optString("point", "").trim())
    val detail = unescapeNewlines(o.optString("detail", "").trim())
    if (point.isBlank() && detail.isBlank()) null
    else ExplainResult(point, detail, o.optString("confidence", "high"))
}.getOrNull()

data class TranslateResult(
    val translation: String, val note: String, val confidence: String
) { val low: Boolean get() = confidence.equals("low", ignoreCase = true) }

fun parseTranslate(raw: String): TranslateResult? = runCatching {
    val o = jsonObject(raw)
    val tr = o.optString("translation", "").trim()
    if (tr.isBlank()) null
    else TranslateResult(tr, o.optString("note", "").trim(), o.optString("confidence", "high"))
}.getOrNull()

data class GeneratedArticle(
    val title: String,
    val content: String,
    val used: List<String>,
    val skipped: List<String>
)

fun parseGenerated(raw: String): GeneratedArticle? = runCatching {
    val o = jsonObject(raw)
    val title = o.optString("title", "").trim().trim('"', '《', '》')
    val content = o.optString("content", "").trim()
    if (content.isBlank()) return null
    GeneratedArticle(
        title = title.ifBlank { "Untitled" },
        content = content,
        used = strArray(o, "used"),
        skipped = strArray(o, "skipped")
    )
}.getOrNull()

fun parsePhonetic(raw: String): PhoneticResult? = runCatching {
    val o = jsonObject(raw)
    val uk = o.optString("uk", "").trim().trim('/')
    val us = o.optString("us", "").trim().trim('/')
    if (uk.isBlank() && us.isBlank()) null
    else PhoneticResult(uk, us, o.optString("confidence", "high"))
}.getOrNull()

fun parseExamples(raw: String): ExampleResult? = runCatching {
    val o = jsonObject(raw)
    ExampleResult(
        items = arr(o, "items").map {
            ExampleItem(
                unescapeNewlines(it.optString("en", "").trim()),
                unescapeNewlines(it.optString("zh", "").trim())
            )
        }.filter { it.en.isNotBlank() },
        confidence = o.optString("confidence", "high")
    )
}.getOrNull()

private fun strArray(o: org.json.JSONObject, key: String): List<String> {
    val a = o.optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(a.length())
    for (i in 0 until a.length()) {
        val v = a.optString(i, "")
        if (v.isNotBlank()) out.add(v)
    }
    return out
}

private fun jsonObject(raw: String): org.json.JSONObject =
    scrubNulls(org.json.JSONObject(stripFence(raw)))

/**
 * 把所有值为 null（或字符串 "null"）的字段改成空串
 *
 * 为什么：Android 自带的 org.json 里 optString(key, fallback) 遇到显式 null
 * 返回的是字面量 "null"，不走 fallback。模型把空字段写成 "base": null 时
 * 下游会拿到一个叫 null 的词，收藏记录、词条名会直接变成 "null"
 *
 * 注：Maven 上那份 org.json 走 fallback，本机测不出来，只有真机如此
 * 所以在唯一入口统一清洗，而不是改几十处 optString
 */
private fun scrubNulls(o: org.json.JSONObject): org.json.JSONObject {
    val keys = o.keys()
    val toFix = ArrayList<String>()
    while (keys.hasNext()) {
        val k = keys.next()
        if (o.isNull(k)) toFix.add(k)
        else if (o.optString(k, "") == "null" && o.opt(k) is String) toFix.add(k)
    }
    for (k in toFix) runCatching { o.put(k, "") }
    // 数组里也要清（items / common 这些）：
    // 对象元素递归清字段，标量元素是 null 就换成空串
    val ks = o.keys()
    while (ks.hasNext()) {
        val k = ks.next()
        val a = o.optJSONArray(k) ?: continue
        for (i in 0 until a.length()) {
            // 注意用 isNull 判断，不能直接用 opt，否则拿到 JSONObject.NULL 对象
            if (a.isNull(i)) runCatching { a.put(i, "") }
            else a.optJSONObject(i)?.let { scrubNulls(it) }
        }
    }
    return o
}
