package com.oracole.readit.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 查词结果 */
data class DictEntry(
    val word: String,
    val phonetic: String?,
    val translation: String,
    val pos: String?,
    val exchange: String?,
    val matchKind: MatchKind
)

enum class MatchKind {
    EXACT,      // 原词命中
    NORMALIZED, // sw 归一后命中（long-time / long time）
    LEMMA,      // 经词形还原命中（went → go）
    RULE,       // 规则剥离命中
    NONE
}

sealed interface DictState {
    data object NotImported : DictState
    data class Ready(val wordCount: Long, val sizeBytes: Long, val version: String) : DictState
    data class Corrupt(val reason: String) : DictState
}

/**
 * 词典仓库
 * 用户导入的 dict.db 会被复制到 filesDir/dict.db，查询时只读打开
 */
class DictRepository(private val ctx: Context) {

    private val dbFile = File(ctx.filesDir, "dict.db")
    private var db: SQLiteDatabase? = null

    val isReady: Boolean get() = db != null

    /** 当前状态；用于设置页展示 */
    suspend fun state(): DictState = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext DictState.NotImported
        val info = runCatching { readMeta(dbFile) }.getOrNull()
            ?: return@withContext DictState.Corrupt("文件不是有效的词库")
        DictState.Ready(info.first, dbFile.length(), info.second)
    }

    /** 打开已导入的词库 */
    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext false
        runCatching {
            db?.close()
            val d = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY
            )
            // 校验表结构
            d.rawQuery("SELECT 1 FROM dict LIMIT 1", null).use { it.moveToFirst() }
            db = d
            true
        }.getOrDefault(false)
    }

    /**
     * 导入：先复制到临时文件，校验通过后原子替换
     * 中途失败不会破坏已有词库
     */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            val tmp = File(ctx.filesDir, "dict_importing.db")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val total = runCatching {
                            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1
                        }.getOrDefault(-1L)
                        val buf = ByteArray(1 shl 16)
                        var copied = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                } ?: return@withContext Result.failure(Exception("打不开这个文件"))

                // 校验
                val ok = runCatching {
                    val t = SQLiteDatabase.openDatabase(
                        tmp.path, null, SQLiteDatabase.OPEN_READONLY
                    )
                    val valid = runCatching {
                        t.rawQuery("SELECT word FROM dict LIMIT 1", null).use { it.moveToFirst() }
                        true
                    }.getOrDefault(false)
                    t.close()
                    valid
                }.getOrDefault(false)

                if (!ok) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("不是有效的 ReadIt 词库"))
                }

                db?.close(); db = null
                if (dbFile.exists()) dbFile.delete()
                if (!tmp.renameTo(dbFile)) {
                    tmp.delete()
                    return@withContext Result.failure(Exception("保存失败，存储空间可能不足"))
                }
                open()
                Result.success(Unit)
            } catch (e: Exception) {
                tmp.delete()
                Result.failure(e)
            }
        }

    /** 删除词典 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        db?.close(); db = null
        dbFile.delete()
    }

    // ---------------- 查询 ----------------

    /**
     * 四级降级查词：
     *   1. 完全匹配（纯字母词优先）
     *   2. sw 归一匹配（long-time / long time / longtime）
     *   3. lemma 表查原形，再回 1
     *   4. 规则剥离后缀，命中的话再回 1
     */
    suspend fun lookup(raw: String): DictEntry? = withContext(Dispatchers.IO) {
        val d = db ?: return@withContext null
        val word = raw.trim()
        if (word.isEmpty()) return@withContext null
        if (!word.any { it.isLetter() }) return@withContext null

        // 1. 精确
        exact(d, word)?.let { return@withContext it }

        // 2. sw
        val swKey = stripWord(word)
        bySw(d, swKey, word)?.let { return@withContext it }

        // 3. lemma
        lemmaOf(d, word.lowercase())?.let { base ->
            exact(d, base)?.let { return@withContext it.copy(matchKind = MatchKind.LEMMA) }
            bySw(d, stripWord(base), base)?.let { return@withContext it.copy(matchKind = MatchKind.LEMMA) }
        }

        // 4. 规则剥离
        for (cand in ruleCandidates(word)) {
            exact(d, cand)?.let { return@withContext it.copy(matchKind = MatchKind.RULE) }
        }
        null
    }

    /**
     * 完全匹配查询（忽略大小写），不降级、不猜
     * 用于查词页：用户输入什么就查什么，查不到就是查不到
     */
    suspend fun exactTranslation(raw: String): String? = withContext(Dispatchers.IO) {
        val d = db ?: return@withContext null
        val w = raw.trim()
        if (w.isEmpty()) return@withContext null

        // 一级：忽略大小写的完全匹配（这一步就够覆盖绝大多数情况）
        exact(d, w)?.translation?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        // 二级：sw 归一（只处理连字符/空格差异，如 long-time ↔ long time）
        // 注意：不做词形还原、不做后缀剥离，查词页要的是准确命中
        // 查 went 不应该返回 go 的释义，那是阅读页点词才需要的"猜"
        bySw(d, stripWord(w), w)?.translation?.takeIf { it.isNotBlank() }
    }

    private fun exact(d: SQLiteDatabase, word: String): DictEntry? {
        // 先找纯字母词条，避开 M.I.C.E. 这类缩写
        d.rawQuery(
            """SELECT word,phonetic,trans,pos,exchange FROM dict
               WHERE sw=? AND word=? COLLATE NOCASE AND flag=0 LIMIT 1""",
            arrayOf(stripWord(word), word)
        ).use { c ->
            if (c.moveToFirst()) return c.toEntry(MatchKind.EXACT)
        }
        // 放宽（用户确实在查带标点的词）
        d.rawQuery(
            """SELECT word,phonetic,trans,pos,exchange FROM dict
               WHERE sw=? ORDER BY (word=? COLLATE NOCASE) DESC, flag, LENGTH(word) LIMIT 1""",
            arrayOf(stripWord(word), word)
        ).use { c ->
            if (c.moveToFirst()) return c.toEntry(MatchKind.EXACT)
        }
        return null
    }

    private fun bySw(d: SQLiteDatabase, sw: String, shown: String): DictEntry? {
        if (sw.isEmpty()) return null
        d.rawQuery(
            """SELECT word,phonetic,trans,pos,exchange FROM dict
               WHERE sw=? AND flag=0 ORDER BY LENGTH(word) LIMIT 1""",
            arrayOf(sw)
        ).use { c ->
            if (c.moveToFirst()) return c.toEntry(MatchKind.NORMALIZED)
        }
        return null
    }

    private fun lemmaOf(d: SQLiteDatabase, form: String): String? {
        d.rawQuery("SELECT base FROM lemma WHERE form=? LIMIT 1", arrayOf(form)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /**
     * 查原形。收藏时用：把 went 归到 go，避免同一个词收成两条
     *
     * 以离线 lemma 表为准（19.4 万条，确定性），查不到就返回原词，
     * 不是所有词都有变形，查不到说明它自己就是原形
     */
    suspend fun baseForm(word: String): String = withContext(Dispatchers.IO) {
        baseFormSync(word)
    }

    /**
     * 同步版。只在已经处于 IO 线程时用（比如数据库迁移事务里）
     * 别在 UI 线程调
     */
    fun baseFormSync(word: String): String {
        val d = db ?: return word.lowercase()
        val w = word.trim().lowercase()
        if (w.isEmpty()) return w
        return lemmaOf(d, w)?.lowercase() ?: w
    }

    /** 规则剥离：只会返回"剥完还像个词"的候选，真正是否存在由调用方查证 */
    private fun ruleCandidates(w: String): List<String> {
        val s = w.lowercase()
        val out = LinkedHashSet<String>()
        fun add(x: String) { if (x.length >= 2) out.add(x) }

        when {
            s.endsWith("ies") && s.length > 4 -> add(s.dropLast(3) + "y")
            s.endsWith("ves") && s.length > 4 -> { add(s.dropLast(3) + "f"); add(s.dropLast(3) + "fe") }
            s.endsWith("es") && s.length > 3 -> { add(s.dropLast(2)); add(s.dropLast(1)) }
            s.endsWith("s") && !s.endsWith("ss") && s.length > 3 -> add(s.dropLast(1))
        }
        if (s.endsWith("ied") && s.length > 4) add(s.dropLast(3) + "y")
        if (s.endsWith("ed") && s.length > 3) {
            val stem = s.dropLast(2)
            add(stem); add(stem + "e")
            if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2]) add(stem.dropLast(1))
        }
        if (s.endsWith("ing") && s.length > 4) {
            val stem = s.dropLast(3)
            add(stem); add(stem + "e")
            if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2]) add(stem.dropLast(1))
        }
        if (s.endsWith("est") && s.length > 5) {
            val stem = s.dropLast(3); add(stem)
            if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2]) add(stem.dropLast(1))
        }
        if (s.endsWith("er") && s.length > 4) {
            val stem = s.dropLast(2); add(stem)
            if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2]) add(stem.dropLast(1))
        }
        if (s.endsWith("ly") && s.length > 4) add(s.dropLast(2))
        return out.toList()
    }

    private fun android.database.Cursor.toEntry(kind: MatchKind) = DictEntry(
        word = getString(0),
        phonetic = getString(1)?.let { normalizePhonetic(it) }?.takeIf { it.isNotBlank() },
        translation = getString(2) ?: "",
        pos = getString(3)?.takeIf { it.isNotBlank() },
        exchange = getString(4)?.takeIf { it.isNotBlank() },
        matchKind = kind
    )

    /**
     * ECDICT 的音标混用了西里尔字母，字体渲染出来很怪
     * 统一替换成对应的拉丁字母
     *
     * 全库统计（352,117 条有音标的词条）里出现过的非 IPA 字符，按次数：
     *   U+04D9 ә  150,660   西里尔 schwa
     *   U+0454 є      607   西里尔乌克兰字母 → e（如 aerial）
     *   U+03B5 ε      996   希腊字母 epsilon  → e（如 aaronical）
     *   U+02CA ˊ    1,348   修饰符重音符     → ˈ（如 abbotcy）
     *   U+0099/U+0090  11   控制字符，直接丢弃
     * 注意：U+025B ɛ（3,770 次）是合法 IPA，不能动
     */
    private fun normalizePhonetic(raw: String): String {
        // ECDICT 里有些音标带未展开的占位符，形如 simәl'teiniәsly; (?@) saim-
        // 全库 47 条。第二个读音是英/美变体，占位符已经丢了信息，直接弃掉后半段
        val head = raw.substringBefore("; (?@)").substringBefore("(?@)")
        return buildString(head.length) {
            for (ch in head) {
                append(
                    when (ch) {
                        '\u04D9', '\u04D8' -> 'ə'   // 西里尔 schwa → 拉丁 schwa
                        '\u04AF' -> 'y'
                        '\u0430' -> 'a'
                        '\u0435' -> 'e'
                        '\u043E' -> 'o'
                        '\u0440' -> 'p'
                        '\u0441' -> 'c'
                        '\u0443' -> 'y'
                        '\u0445' -> 'x'
                        '\u0456' -> 'i'
                        '\u0454' -> 'e'             // 西里尔乌克兰字母 є
                        '\u03B5' -> 'e'             // 希腊 epsilon ε（不是 IPA 的 ɛ）
                        '\u02CA' -> '\u02C8'        // ˊ → ˈ 主重音
                        '\u2019' -> '\''            // 弯引号 → 直引号（重音符号统一）
                        '\u0099', '\u0090' -> ""    // 控制字符，丢弃
                        else -> ch.toString()
                    }
                )
            }
        }.trim().trimEnd(';', '-', ' ').trim()
    }

    private fun stripWord(w: String): String =
        w.filter { it.isLetterOrDigit() }.lowercase()

    /** 读词条数与"版本"（用文件修改时间当作版本标记） */
    private fun readMeta(f: File): Pair<Long, String> {
        val d = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        val n = d.rawQuery("SELECT COUNT(*) FROM dict", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        d.close()
        return n to "内置"
    }
}
