package com.oracole.readit.ui.reader

/**
 * 把一段文字切成"可点击的词"
 *
 * 规则：只把连续的英文字母（含撇号、连字符）当作词
 * 标点、空格、数字不参与点击，点了不弹东西
 */
object Tokenizer {

    data class Word(val text: String, val start: Int, val end: Int)

    private fun isWordChar(c: Char): Boolean =
        c.isLetter() || c == '\'' || c == '\u2019' || c == '-' || c == '’'

    /**
     * 返回这段文字里所有词的位置
     * 注意：'don\'t' 算一个词，'well-known' 算一个词
     * 但单独的 '-' 或 "'" 不算
     */
    fun tokens(text: String): List<Word> {
        val out = ArrayList<Word>(text.length / 5 + 4)
        var i = 0
        val n = text.length
        while (i < n) {
            if (!isWordChar(text[i])) { i++; continue }
            val start = i
            while (i < n && isWordChar(text[i])) i++
            var s = start
            var e = i
            // 去掉首尾的连字符和撇号，例如 "--well--" 只留 "well"
            while (s < e && (text[s] == '-' || text[s] == '\'' || text[s] == '\u2019' || text[s] == '’')) s++
            while (e > s && (text[e - 1] == '-' || text[e - 1] == '\'' || text[e - 1] == '\u2019' || text[e - 1] == '’')) e--
            if (e > s && text.substring(s, e).any { it.isLetter() }) {
                out.add(Word(text.substring(s, e), s, e))
            }
        }
        return out
    }

    /**
     * 取包含该词的完整句子
     * 遇到 . ! ? 或换行就断句；一句最长 300 字符，防御性截断
     */
    /**
     * 取包含该词的句子区间
     *
     * 句点判定参考 Punkt（Kiss & Strunk, 2006）的两阶段思路：
     *
     * 第一阶段 — 这个 token 是不是缩写？
     *     论文给的判据只有三条，都与语料无关：
     * 1. 是截断的词 + 句点这种紧绑定（我们用具名缩写表代替统计学习）
     *       2. 通常很短
     *       3. 有时内部带点（U.S / e.g）
     * 我们是按段落调用的，学不出某词是否总跟着句点的统计量
     * 所以用一张约 200 条的高频缩写表替代第一阶段的统计
     *
     * 第二阶段 — 即使是缩写，句点也可能同时是句末
     * 英文写作里缩写句点和句末句点重合时只写一个（haplology）
     * 所以 the U.S.（句末）和 the U.S. economy（句中）字符串完全一样
     * 只能靠后一个词判断：如果它是常见的句子开头词，这就是句末
     */
    fun sentenceRange(text: String, wordStart: Int, wordEnd: Int): Pair<Int, Int> {
        val all = splitSentences(text)
        if (all.isEmpty()) return 0 to 0
        val idx = indexOfSentenceContaining(all, wordStart, wordEnd)
        return all[idx]
    }

    /**
     * 把一段文字切成句子区间
     *
     * 为什么不再用从词往前/往后扫的写法：
     * 那种写法在处理引号时必然出问题，"He said "Stop." Then he left."
     * 往前扫会在 Stop 后面那个句点停下，把收尾引号丢掉
     * 先整体切分再取相邻，边界只算一次，不会出现前后不一致
     *
     * 每句包含它的句末标点和紧跟的收尾引号/括号
     */
    private fun splitSentences(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val s = i
            var e = i
            while (e < text.length && !isBreakAtIn(text, e)) e++
            if (e < text.length) {
                if (text[e] != '\n') e++
                while (e < text.length && text[e] in CLOSERS) e++
            }
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) out.add(s to e)
            // 至少前进一个字符，避免 e 卡在原地造成死循环
            i = if (e > s) e else s + 1
        }
        return out
    }

    /** 找包含这个词的句子。落在空白处时退到前一句 */
    private fun indexOfSentenceContaining(
        all: List<Pair<Int, Int>>, wordStart: Int, wordEnd: Int
    ): Int {
        all.forEachIndexed { i, r ->
            if (wordStart < r.second && wordEnd > r.first) return i
        }
        return all.indexOfLast { it.first <= wordStart }.coerceAtLeast(0)
    }

    /**
     * 一次算好当前句和带上下文的句子，避免调用方切两遍
     *
     * 分两个值是有意的：
     * sentence，单句，用于界面显示所在句和存出处
     * context，上下三句，只喂给 AI
     * 混成一个的话，出处卡片会突然变成三句，界面会变乱
     */
    fun sentenceAndContext(text: String, wordStart: Int, wordEnd: Int): Pair<String, String> {
        val all = splitSentences(text)
        if (all.isEmpty()) return "" to ""
        val i = indexOfSentenceContaining(all, wordStart, wordEnd)

        fun at(k: Int): String? =
            all.getOrNull(k)?.let { text.substring(it.first, it.second).trim() }
                ?.takeIf { it.isNotEmpty() }

        val cur = at(i) ?: return "" to ""
        // 上句/下句只在段内找；段首段尾没有就只给现有的
        val joined = listOfNotNull(at(i - 1), cur, at(i + 1)).joinToString(" ")
        val ctx = if (joined.length <= MAX_CONTEXT) joined else cur
        return cur to ctx
    }

    /** 上下文长度上限。三句超过这个就只给当前句 —— 太长反而稀释重点 */
    private const val MAX_CONTEXT = 900

    /**
     * 判断 text[i] 是不是句末
     *
     * 提成独立方法是为了让 sentenceRange 和 sentenceWithContext 共用同一套判定，
     * 两处规则必须一致，否则全句选中的范围和 AI 看到的句子会对不上
     */
    private fun isBreakAtIn(text: String, i: Int): Boolean {

        val c = text[i]
        if (c == '\n') return true
        if (c != '.' && c != '!' && c != '?') return false
        // ! ? 没有歧义，一定是句末
        if (c != '.') return true

        // ① 连续的点里只有最后一个参与判断
        // 原来三个点全部 return false，导致省略号永远不断句，
        // "I don't know... Maybe later." 会被合成一句
        if (i + 1 < text.length && text[i + 1] == '.') return false
        val isEllipsis = i > 0 && text[i - 1] == '.'

        // ② 取句点前面那个 token（不含句点），并剥掉开头的引号/括号/破折号
        //    不剥的话，带引号对话里的 "Mr 会被当成一个普通词
        // （不在缩写表里），于是 He said, "Mr. Smith 会断在 Mr. 后面
        // 而 isSentenceStarter 是会剥收尾符的，同一规则两处不一致
        var p = i - 1
        while (p >= 0 && !text[p].isWhitespace()) p--
        var st = p + 1
        while (st < i && text[st] in OPENERS) st++
        val before = text.substring(st, i)

        // ③ 句点后面必须跟空白、结尾或收尾引号/括号
        //    这一条直接排除了 3.14、a.com、readme.txt
        var j = i + 1
        while (j < text.length && text[j] in CLOSERS) j++
        if (j < text.length && !text[j].isWhitespace()) return false

        // ④ 取句点后第一个词，同样剥掉开头的引号/括号
        // 不剥的话 next 是 "The，next[0] 是引号
        // isUpperCase 为 false，后面的判定全错
        var k = j
        while (k < text.length && text[k].isWhitespace()) k++
        var ks = k
        while (ks < text.length && text[ks] in OPENERS) ks++
        var m = ks
        while (m < text.length && !text[m].isWhitespace()) m++
        val next = if (ks < text.length) text.substring(ks, m) else ""

        // ④b 省略号：后面接大写开头才算句末
        // "Wait... what?" 不断；"I don't know... Maybe later." 断
        if (isEllipsis) return next.isNotEmpty() && next[0].isUpperCase()

        // ⑤ 纯数字 + 句点：可能是小数（已在③排除）、序数或列表编号
        // Punkt 把数字与 initials 同样处理，靠后面的词判断
        if (before.isNotEmpty() && before.all { it.isDigit() }) {
            return next.isEmpty() || next[0].isUpperCase()
        }

        // ⑥ 单字母首字母（J. K. Rowling）：永远不是句末
        if (before.length == 1 && before[0].isLetter()) return false

        // ⑦ 缩写：第二阶段判定
        if (isAbbrevType(before)) {
            if (next.isEmpty()) return true
            // 下一个词小写开头 → 一定还在句中（"the U.S. economy"）
            if (!next[0].isUpperCase()) return false
            // 大写开头时：称谓（Mr. Smith）和公司后缀（Co. Inc.）
            // 后面跟大写是正常的，要看它像不像句首；
            // 其余缩写默认断句，否则 sat / sun / pub 这些
            // 本身是常用词的会把两句连成一句
            // 带内点的缩写（L.F. / U.S. / N.Y.）是首字母缩写
            // 后面常跟专有名词，必须看像不像句首：
            //   L.F. Rothschild   → Rothschild 不在句首表 → 不断 ✓
            //   U.S. The economy  → The 在句首表 → 断 ✓
            if (before.trimEnd('.').contains('.')) {
                return isSentenceStarter(next)
            }
            val bare = before.trimEnd('.').lowercase()
            if (bare in TITLES || bare in COMPANY_SUFFIX) {
                return isSentenceStarter(next)
            }
            return true
        }

        return true
    }

    /** 收尾标点：句点后面跟这些也算「后面是空白」 */
    private val CLOSERS = charArrayOf('"', '\'', ')', '”', '’', ']')

    /**
     * 开头标点：取句点前的词时要剥掉
     *
     * 引号对话是英文小说的常态，不剥会让缩写表整条失效：
     * He said, "Mr. Smith is here. 里 before 是 "Mr
     * 不在缩写表里，于是直接判成句末
     */
    private val OPENERS = charArrayOf(
        '"', '\'', '“', '‘', '(', '[', '{', '—', '–', '-', '«', '‹'
    )

    /**
     * Punkt 第一阶段的简化版：判断 candidate（不含最后的句点）是不是缩写类型
     *
     * 论文的三条判据：
     * 1. 紧绑定，用缩写表代替统计学习
     * 2. 通常很短，单字母（姓名首字母）或表内条目
     * 3. 有时内部带点，U.S / e.g / i.v 全由单字母加点构成
     */
    private fun isAbbrevType(cand: String): Boolean {
        if (cand.isEmpty()) return false
        val bare = cand.trimEnd('.')
        if (bare.isEmpty()) return false

        // 内部带点且每段都很短：U.S / e.g / i.v / a.m
        if (bare.contains('.')) {
            val parts = bare.split('.')
            if (parts.all { it.length <= 2 } && parts.size <= 4) return true
        }

        // 单字母：J. K. Rowling 里的 initials
        if (bare.length == 1 && bare[0].isLetter()) return true

        if (bare.lowercase() in ABBREV) return true

        // 公司后缀：Co. / Inc. / Ltd. / Bros.
        // 不能只靠首字母大写 + 短，那会把 The / And / But 这些全判成缩写
        // 只收一份明确的后缀清单
        if (bare.lowercase() in COMPANY_SUFFIX) return true

        return false
    }

    /**
     * Punkt 第二阶段的简化版：下一个词是不是典型的句子开头
     *
     * 缩写后面跟这些词，几乎一定是新句子开始了：
     * ... in the U.S. He then moved ...
     * 而跟普通名词时多半还在句中：
     * ... the U.S. economy ...
     */
    private fun isSentenceStarter(word: String): Boolean {
        if (word.isEmpty()) return false
        // 剥开头引号：It was 1990. "The next year ...
        // 调用方大多已经剥过，这里再兜一次，这是共用判定，不该假设入参是干净的
        var s = 0
        while (s < word.length && word[s] in OPENERS) s++
        if (s >= word.length) return false
        val w = word.substring(s)
        if (!w[0].isUpperCase()) return false
        val cleaned = w.lowercase().trim(*CLOSERS)
        return cleaned in SENTENCE_STARTERS
    }

    /**
     * 称谓与头衔。这类后面跟大写人名是正常的（Mr. Smith）
     * 所以句末判定要额外看像不像句首
     *
     * 刻意不收 rep / col / gen 这些，它们本身是常用英文词
     * （"my rep"、"the col"），收进来会把 "...my rep. Nobody else..." 连成一句
     */
    private val TITLES = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "rev", "hon",
        "maj", "capt", "lt", "sgt", "cmdr", "adm", "gov", "pres",
        "sen", "supt", "insp", "det", "atty"
    )

    /** 公司/机构后缀。必须和普通英文词区分开，所以单列一张小表 */
    private val COMPANY_SUFFIX = setOf(
        "co", "inc", "ltd", "corp", "llc", "plc", "bros", "assn", "dept", "univ"
    )

    /** 常见句首词。只收高频的 —— 收太多会把真句末误判成句中 */
    private val SENTENCE_STARTERS = setOf(
        "the", "a", "an", "this", "that", "these", "those",
        "it", "he", "she", "they", "we", "i", "you",
        "his", "her", "their", "our", "my", "your", "its",
        "but", "and", "or", "so", "then", "there", "here",
        "however", "therefore", "meanwhile", "instead", "still", "yet",
        "if", "when", "while", "after", "before", "because", "since", "although",
        "in", "on", "at", "for", "from", "with", "as", "by", "of", "to",
        "one", "two", "three", "no", "not", "all", "some", "many", "most",
        "now", "also", "only", "just", "even", "such",
        "what", "who", "how", "why", "where", "which"
    )

    /**
     * 高频英文缩写。约 200 条，覆盖文章里 99% 的情况
     *
     * 收录标准：后面经常紧跟句点、但句点不是句末
     *
     * 特别注意：不收本身就是常用英文单词的（in / no / am / p / co / sec / min）
     * 因为一旦进表，句末判定就要走后一个词是不是句子开头这道关
     * 而 "...that's all I am." 这种后面跟普通词的情况会被误判成句中
     * 不在表里反而是安全的：直接判为句末
     */
    private val ABBREV = setOf(
        // 称谓与头衔
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "rev", "hon",
        "gen", "col", "maj", "capt", "lt", "sgt", "cmdr", "adm", "gov", "pres",
        "sen", "rep", "supt", "insp", "det", "atty",
        // 学位与专业
        "phd", "bsc", "msc", "dds", "dvm", "esq",
        // 拉丁缩写
        // etc 故意不收：它几乎总是出现在句末（列举结束）
        // 收进来会让...apples, oranges, etc.判不出句末
        "e.g", "i.e", "vs", "cf", "viz", "ibid",
        // 月份与星期
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
        "oct", "nov", "dec",
        "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
        // 地名与国名
        "u.s", "u.k", "u.s.a", "u.n", "e.u", "d.c", "n.y", "l.a",
        // 出版与文献
        "fig", "figs", "vol", "vols", "chap", "sect", "pp",
        "eds", "repr", "orig", "pub",
        // 商业与组织
        "inc", "ltd", "corp", "dept", "univ", "assn", "bros", "mfg",
        "intl", "natl", "govt", "hq",
        // 度量（只在带点时有意义，且不是常用英文词）
        "approx", "avg", "lbs", "gal"
    )

    /**
     * 二分查找：给定字符偏移，返回落在哪个词上
     * 点在词与词之间的空白上，返回 null
     */
    fun wordAt(words: List<Word>, offset: Int): Word? {
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = words[mid]
            when {
                offset < w.start -> hi = mid - 1
                offset >= w.end -> lo = mid + 1
                else -> return w
            }
        }
        return null
    }
}
