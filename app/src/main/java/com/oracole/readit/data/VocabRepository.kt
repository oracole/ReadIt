package com.oracole.readit.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 一个单词 + 它的出处 */
data class VocabItem(
    val id: Long,
    val word: String,
    val displayWord: String,
    val phonetic: String?,
    val translation: String,
    val status: Int,          // 0 生词, 1 熟词
    val createdAt: Long,
    val updatedAt: Long,
    val knownAt: Long?,
    /** 见过的形态，逗号分隔（went,goes,going） */
    val forms: String? = null,
    val contexts: List<VocabContext> = emptyList()
) {
    /** 已收藏的形态列表，不含原词本身 */
    val formList: List<String>
        get() = forms?.split(',')?.map { it.trim() }?.filter {
            it.isNotEmpty() && !it.equals(displayWord, ignoreCase = true)
        } ?: emptyList()
    val isKnown: Boolean get() = status == 1
    /** 列表里显示的一行释义：只取第一行 */
    val oneLine: String get() = translation.lineSequence().firstOrNull()?.trim() ?: ""

    /**
     * 用户点这个词时，能不能落到这条记录上
     *
     * 按原形收藏打开后，记录里的 word 可能是原形（收藏 means 存成 mean）
     * 此时只用 word 比较会漏，用户点在正文里的永远是 means 这种形态
     * 所以必须同时认 forms 里的形态
     *
     * 这是这个词是否属于这条记录的唯一判定，不要在别处另写一份
     */
    fun matches(word: String): Boolean =
        this.word.equals(word.trim(), ignoreCase = true) ||
            formList.any { it.equals(word.trim(), ignoreCase = true) }
}

data class VocabContext(
    val id: Long,
    val articleId: Long?,
    val articleTitle: String?,
    val sentence: String,
    val wordForm: String?
)

class VocabRepository(private val ctx: Context) {

    private val helper by lazy { VocabDatabase(ctx) }

    private val _items = MutableStateFlow<List<VocabItem>>(emptyList())
    val items: StateFlow<List<VocabItem>> = _items.asStateFlow()

    // 已收藏词集合，随 items 一起变化，供 UI 响应式读取
    private val _collected = MutableStateFlow<Set<String>>(emptySet())
    val collected: StateFlow<Set<String>> = _collected.asStateFlow()

    /**
     * 一次性迁移：把历史上按形态分开收的重复记录并成一条
     *
     * 背景：早期收藏不做归一，收藏 running 和 run 会建两条独立记录
     * 现在改成按原形归一了，但老数据还在，用户会看到同一个词出现两次
     *
     * 规则（保守）：
     * - base 记录已存在 → 出处处迁过去、forms 合并、状态取生词、删掉多余那条
     *   - base 记录不存在 → 只把 word / display_word 改成原形（不动其他字段）
     *   - 全程一个事务，出错自动回滚
     *   - 词典没导入时 baseForm 返回原词，等于什么都不做，安全
     *
     * 幂等：已归一的数据每组只有一条，直接跳过
     */
    suspend fun mergeDuplicatesOnce(dictRepo: DictRepository) = withContext(Dispatchers.IO) {
        // ⚠️ 这个方法当前没有任何调用点，从未执行过
        // 它做的是按原形合并重复记录，而后来定的规则是收藏不按原形归一
        // （35 个常见变形词里 33 个有独立词性）。合并不可逆，所以没有默认启用
        // 保留实现是为了将来真要合并时有现成的，不要照旧注释说的去调用
        //
        // 以下为它当初的设计意图（已不适用）：
        // 不设已完成标记：这个操作是幂等的，每次启动跑一遍最安全
        // 理由：词典可能在首次启动之后才导入。如果第一次跑完就标记完成
        // 那时词典还没就绪、baseForm 全部返回原词、什么都没迁
        // 之后导入词典也永远不会再迁了
        //
        // 幂等的保证：已经归一过的数据，每组只有一条且 word == base
        // 直接 continue，成本是每组一次索引查询
        val db = helper.writableDatabase
        db.beginTransaction()
        // 迁移失败不该让 App 起不来：捕获所有异常，回滚，不标记完成（下次再试）
        try {
            // 1. 读全部记录
            data class Row(
                val id: Long, val word: String, val display: String,
                val forms: String?, val status: Int,
                val createdAt: Long, val updatedAt: Long, val knownAt: Long?
            )
            val rows = ArrayList<Row>()
            db.rawQuery(
                """SELECT id,word,display_word,forms,status,created_at,updated_at,known_at
                   FROM vocab ORDER BY id""", null
            ).use { c ->
                while (c.moveToNext()) {
                    rows.add(
                        Row(
                            c.getLong(0), c.getString(1), c.getString(2),
                            c.getString(3), c.getInt(4),
                            c.getLong(5), c.getLong(6),
                            if (c.isNull(7)) null else c.getLong(7)
                        )
                    )
                }
            }

            // 2. 按原形分组
            val groups = LinkedHashMap<String, MutableList<Row>>()
            rows.forEach { r ->
                val b = dictRepo.baseFormSync(r.word)
                groups.getOrPut(b) { ArrayList() }.add(r)
            }

            // 3. 逐组处理
            groups.forEach { (base, members) ->
                if (members.size <= 1 && members[0].word.equals(base, ignoreCase = true)) {
                    return@forEach   // 本来就是单独一条原形，跳过
                }
                // 保留哪条：优先 word 就是原形的那条，否则 id 最小的
                val keep = members.firstOrNull { it.word.equals(base, ignoreCase = true) }
                    ?: members.minByOrNull { it.id }!!
                val drop = members.filter { it.id != keep.id }

                // 合并 forms：原词的形态 + 被删记录的形态 + 被删记录的 word
                val allForms = LinkedHashSet<String>()
                members.forEach { m ->
                    m.forms?.split(',')?.forEach { f ->
                        val t = f.trim().lowercase()
                        if (t.isNotEmpty() && t != base) allForms.add(t)
                    }
                    if (!m.word.equals(base, ignoreCase = true)) {
                        allForms.add(m.word.lowercase())
                    }
                }

                // 把被删记录的出处处迁到保留记录
                drop.forEach { d ->
                    db.execSQL(
                        "UPDATE vocab_context SET vocab_id=? WHERE vocab_id=?",
                        arrayOf(keep.id, d.id)
                    )
                }

                // 释义也要换成原形的
                // 历史上收藏 scheduled 时存的是a. 预定的（形容词）
                // 归到 schedule 那条之后，用户看到 schedule 配预定的会觉得莫名其妙
                // 原形查得到就用原形的释义，查不到保留原来的
                val baseTrans = dictRepo.exactTranslation(base)?.takeIf { it.isNotBlank() }

                // 保留记录：统一 word / display_word / forms / status / 时间
                val knownStatus = if (members.all { it.status == 1 }) 1 else 0
                db.update("vocab", ContentValues().apply {
                    put("word", base.lowercase())
                    put("display_word", if (keep.word.equals(base, true)) keep.display else base)
                    put("status", knownStatus)
                    if (baseTrans != null) put("translation", baseTrans)
                    put("forms", allForms.joinToString(",").ifBlank { null })
                    put("created_at", members.minOf { it.createdAt })
                    put("updated_at", members.maxOf { it.updatedAt })
                    if (knownStatus == 0) putNull("known_at")
                    else put("known_at", members.mapNotNull { it.knownAt }.minOrNull())
                }, "id=?", arrayOf(keep.id.toString()))

                // 删掉多余的
                drop.forEach { d ->
                    db.delete("vocab", "id=?", arrayOf(d.id.toString()))
                }
            }

            db.setTransactionSuccessful()
        } catch (e: Exception) {
            // 失败就算了：老数据还在，只是没并成一条，下次启动会重试
            android.util.Log.w("VocabRepository", "合并重复记录失败", e)
        } finally {
            runCatching { db.endTransaction() }
        }
        // 迁移后重新加载，让 UI 立刻看到合并结果
        load()
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val list = ArrayList<VocabItem>()
        db.rawQuery(
            """SELECT id,word,display_word,phonetic,translation,status,
                      created_at,updated_at,known_at,forms
               FROM vocab ORDER BY updated_at DESC""", null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    VocabItem(
                        id = c.getLong(0),
                        word = c.getString(1),
                        displayWord = c.getString(2),
                        phonetic = c.getString(3)?.takeIf { it.isNotBlank() },
                        translation = c.getString(4) ?: "",
                        status = c.getInt(5),
                        createdAt = c.getLong(6),
                        updatedAt = c.getLong(7),
                        knownAt = if (c.isNull(8)) null else c.getLong(8),
                        forms = c.getString(9)?.takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        // 出处
        val ctxMap = HashMap<Long, MutableList<VocabContext>>()
        db.rawQuery(
            """SELECT id,vocab_id,article_id,article_title,sentence,word_form
               FROM vocab_context ORDER BY created_at DESC""", null
        ).use { c ->
            while (c.moveToNext()) {
                val vid = c.getLong(1)
                ctxMap.getOrPut(vid) { ArrayList() }.add(
                    VocabContext(
                        id = c.getLong(0),
                        articleId = if (c.isNull(2)) null else c.getLong(2),
                        articleTitle = c.getString(3),
                        sentence = c.getString(4) ?: "",
                        wordForm = c.getString(5)
                    )
                )
            }
        }
        // 排序：生词在前，熟词在后；组内按最近更新倒序
        // 这样标熟后条目只会往下走，不会因为"刚操作过"而跳到顶部
        _items.value = list
            .map { it.copy(contexts = ctxMap[it.id] ?: emptyList()) }
            .sortedWith(compareBy({ it.isKnown }, { -it.updatedAt }))
        // 集合里同时放原形和所有见过的形态
        //
        // 为什么：收藏按原形归一，记录是 run，但用户点的可能是 running
        // 如果只放原形，任何地方用 word 判断都会显示"未收藏"
        // 点了还会重复收藏、取消不掉
        //
        // 放进来之后，调用方不管手里是 run 还是 running，判断都对
        // 不需要每处都先算一遍 base（算 base 要查词典，是 IO）
        _collected.value = buildSet {
            list.forEach { item ->
                add(item.word.lowercase())
                item.forms?.split(',')?.forEach { f ->
                    val t = f.trim().lowercase()
                    if (t.isNotEmpty()) add(t)
                }
            }
        }
    }

    fun isCollected(word: String): Boolean = word.lowercase() in _collected.value

    /**
     * 按词或它的任一形态找收藏记录
     *
     * 取消收藏必须用它：收藏时可把 means 归到 mean 名下（forms 里记着 means）
     * 此时记录里 word 是 mean。只用 word 匹配会找不到
     * 表现就是点了取消收藏什么也没发生，但 toast 照样弹
     */
    fun findByWordOrForm(word: String, base: String? = null): VocabItem? {
        if (word.isBlank()) return null

        // 先按 base 精确找
        // found 可能同时存在两条记录：find（forms 含 found）和独立的 found
        // 只拿 surface form 找会命中排序靠前的任意一条，取消收藏就可能删错
        val b = base?.trim()?.takeIf { it.isNotBlank() }
        if (b != null) {
            items.value.firstOrNull { it.word.equals(b, ignoreCase = true) }
                ?.let { return it }
        }

        // 没有 base 时先找 word 完全相同的记录，再退化到 forms
        // 顺序和 addContext 保持一致：word 命中的是这个词自己的记录
        // forms 命中的是它作为别的词的形态被记下来
        items.value.firstOrNull { it.word.equals(word.trim(), ignoreCase = true) }
            ?.let { return it }

        return items.value.firstOrNull { it.matches(word) }
    }

    /** 收藏。已存在则只补一条出处 */
    /**
     * @param base 原形（went 传 go）。不传就按 word 自己算原形
     * @param word 用户实际点的形态（went）
     */
    suspend fun collect(
        word: String,
        phonetic: String?,
        translation: String,
        sentence: String,
        articleId: Long?,
        articleTitle: String?,
        wordForm: String?,
        base: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        // 按原形归一：收藏 went 时建的是 go 那条记录
        // 这样同一个词的不同形态不会在列表里重复出现
        val key = (base ?: word).lowercase()

        val existingId = db.rawQuery(
            "SELECT id FROM vocab WHERE word=? COLLATE NOCASE", arrayOf(key)
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }

        // 用户这次点的形态（和原形相同则不记）
        val thisForm = word.lowercase().takeIf { it != key }

        val id = if (existingId > 0) {
            // 已存在：把新形态并进 forms，不重复
            // 收藏 went 后又遇到 goes，go 那条记录的 forms 会变成 "went,goes"
            val oldForms = db.rawQuery(
                "SELECT forms FROM vocab WHERE id=?", arrayOf(existingId.toString())
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            val merged = (oldForms.orEmpty().split(',')
                .map { it.trim() } + listOfNotNull(thisForm))
                .filter { it.isNotEmpty() }
                .distinct()
            db.update("vocab", ContentValues().apply {
                put("updated_at", now)
                if (merged.isNotEmpty()) put("forms", merged.joinToString(","))
            }, "id=?", arrayOf(existingId.toString()))
            existingId
        } else {
            db.insert("vocab", null, ContentValues().apply {
                put("word", key)
                // display_word 存原形，列表里显示 go 而不是 went
                put("display_word", if (base != null) base else word)
                put("phonetic", phonetic)
                put("translation", translation)
                put("status", 0)
                put("created_at", now)
                put("updated_at", now)
                put("forms", thisForm)
            })
        }

        // 同一句子不重复添加
        val dup = db.rawQuery(
            "SELECT id FROM vocab_context WHERE vocab_id=? AND sentence=?",
            arrayOf(id.toString(), sentence)
        ).use { it.moveToFirst() }

        if (!dup) {
            db.insert("vocab_context", null, ContentValues().apply {
                put("vocab_id", id)
                put("article_id", articleId)
                put("article_title", articleTitle)
                put("sentence", sentence)
                put("word_form", wordForm)
                put("created_at", now)
            })
        }

        load()
        id
    }

    /** 词汇表导入的一行 */
    data class ImportRow(val word: String, val meaning: String, val status: Int)

    data class ImportTally(val inserted: Int, val skippedExisting: Int)

    /**
     * 批量导入词汇。整批放进一个事务，结束只调一次 load()
     *
     * 不能循环调 collect()：那个方法每条结尾都会 load() 重载整张表
     * 导入一千个词就要重载一千次，界面会卡死
     *
     * 已存在的词直接跳过，不覆盖，已有的释义和出处比文件里的更有价值
     * 导入的词不写 vocab_context，所以详情页的出处区块不会出现
     */
    suspend fun importBatch(rows: List<ImportRow>): ImportTally = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        var inserted = 0
        var skipped = 0
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            for (r in rows) {
                val key = r.word.lowercase()
                val exists = db.rawQuery(
                    "SELECT id FROM vocab WHERE word=? COLLATE NOCASE", arrayOf(key)
                ).use { it.moveToFirst() }
                if (exists) { skipped++; continue }

                db.insert("vocab", null, ContentValues().apply {
                    put("word", key)
                    put("display_word", r.word)
                    put("phonetic", null as String?)
                    put("translation", r.meaning)
                    put("status", r.status)
                    put("created_at", now)
                    put("updated_at", now)
                    put("forms", null as String?)
                })
                inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // 只重载一次
        if (inserted > 0) load()
        ImportTally(inserted, skipped)
    }

    /**
     * 把一个形态记进某条收藏的 forms
     *
     * 用于按原形收藏打开时的场景：
     *   - 收藏时存的是 find，用户点的其实是 found → 记下 found
     *   - 已收藏的词，点开语境义 → 也记下当时点的形态
     * 详情页那行绿色小字就是这个字段，让用户知道词表里的词是怎么来的
     *
     * 找不到记录 / 形态等于词本身 / 已经记过，都直接返回 false，不写库
     */
    suspend fun addForm(base: String, form: String): Boolean = withContext(Dispatchers.IO) {
        val b = base.trim().lowercase()
        val f = form.trim().lowercase()
        if (b.isEmpty() || f.isEmpty() || b == f) return@withContext false

        val db = helper.writableDatabase
        val id = db.rawQuery(
            "SELECT id,forms FROM vocab WHERE word=? COLLATE NOCASE", arrayOf(b)
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (id <= 0) return@withContext false

        val old = db.rawQuery(
            "SELECT forms FROM vocab WHERE id=?", arrayOf(id.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }

        val list = old.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (f in list.map { it.lowercase() }) return@withContext false

        val merged = (list + f).joinToString(",")
        db.update("vocab", ContentValues().apply {
            put("forms", merged)
        }, "id=?", arrayOf(id.toString()))
        load()
        true
    }

    /**
     * 只追加一条出处，不创建词条、不改动词条任何字段
     *
     * 用于已收藏的词在别的文章里再次被查询的场景：
     * 此时收藏按钮显示的是取消收藏，走不到 collect()，出处永远补不上
     * 已有的词典释义也不应被覆盖，所以这里刻意不碰 vocab 表
     */
    suspend fun addContext(
        word: String,
        sentence: String,
        articleId: Long?,
        articleTitle: String?,
        wordForm: String?,
        /**
         * 这个词在当前语境下的原形，来自 AI 的判定。可为空
         *
         * 为什么需要：found 可能同时存在两条记录，一条是 find（forms 里含 found）
         * 另一条是独立的 found（当建立用）。只拿 surface form 无法分辨
         * 出处会被记到错误的那条下面。有了 base 就能精确定位
         */
        base: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (sentence.isBlank()) return@withContext false
        val db = helper.writableDatabase
        val w = word.lowercase()

        fun idOf(key: String): Long = db.rawQuery(
            "SELECT id FROM vocab WHERE word=? COLLATE NOCASE", arrayOf(key)
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }

        fun idByForm(key: String): Long = db.rawQuery(
            // forms 是逗号分隔。两端都补逗号再 LIKE，
            // 只补开头的话，forms 里只有单个词（没有逗号）时永远匹配不上
            "SELECT id FROM vocab " +
                "WHERE (',' || LOWER(REPLACE(forms,' ','')) || ',') LIKE ? LIMIT 1",
            arrayOf(formLikePattern(key))
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }

        // 定位顺序：
        //   ① 有 base → 按 base 找（最准，能区分 found 的两种身份）
        //   ② 按 word 精确找（这个词自己就是一条记录）
        //   ③ 按 forms 反查（收藏时被归约到了原形名下）
        val id = (base?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { idOf(it) }
            ?: -1L)
            .takeIf { it > 0 }
            ?: idOf(w).takeIf { it > 0 }
            ?: idByForm(w)

        if (id <= 0) return@withContext false   // 不是收藏词，什么都不做

        val dup = db.rawQuery(
            "SELECT id FROM vocab_context WHERE vocab_id=? AND sentence=?",
            arrayOf(id.toString(), sentence)
        ).use { it.moveToFirst() }
        if (dup) return@withContext false

        db.insert("vocab_context", null, ContentValues().apply {
            put("vocab_id", id)
            put("article_id", articleId)
            put("article_title", articleTitle)
            put("sentence", sentence)
            put("word_form", wordForm)
            put("created_at", System.currentTimeMillis())
        })
        load()
        true
    }

    /** 取消收藏（连同出处一起删） */
    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("vocab", "id=?", arrayOf(id.toString()))
        load()
    }

    /** 切换生词/熟词 */
    suspend fun setStatus(id: Long, known: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.update("vocab", ContentValues().apply {
            put("status", if (known) 1 else 0)
            put("known_at", if (known) now else null)
            put("updated_at", now)
        }, "id=?", arrayOf(id.toString()))
        load()
    }

    /** 批量标熟 */
    suspend fun setStatusBatch(ids: List<Long>, known: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.update("vocab", ContentValues().apply {
                    put("status", if (known) 1 else 0)
                    put("known_at", if (known) now else null)
                    put("updated_at", now)
                }, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        load()
    }

    /** 批量取消收藏 */
    suspend fun removeBatch(ids: List<Long>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.delete("vocab", "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        load()
    }

    // ---------------- 词形 ----------------

    /**
     * 读缓存的词形。没有就返回 null
     * 和 AI 缓存不同：这里长期保存，清缓存不会丢
     */
    suspend fun loadForms(word: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT payload FROM vocab_form WHERE word=? COLLATE NOCASE",
            arrayOf(word.lowercase())
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun saveForms(word: String, payload: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertWithOnConflict(
            "vocab_form", null,
            ContentValues().apply {
                put("word", word.lowercase())
                put("payload", payload)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    // ---------------- 问 AI 历史 ----------------

    data class AskRecord(
        val id: Long, val question: String, val answer: String, val createdAt: Long
    )

    suspend fun loadAsks(word: String): List<AskRecord> = withContext(Dispatchers.IO) {
        val out = ArrayList<AskRecord>()
        helper.readableDatabase.rawQuery(
            """SELECT id,question,answer,created_at FROM ai_ask
               WHERE word=? COLLATE NOCASE ORDER BY created_at DESC""",
            arrayOf(word.lowercase())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(AskRecord(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        out
    }

    suspend fun addAsk(word: String, question: String, answer: String): Long =
        withContext(Dispatchers.IO) {
            helper.writableDatabase.insert("ai_ask", null, ContentValues().apply {
                put("word", word.lowercase())
                put("question", question)
                put("answer", answer)
                put("created_at", System.currentTimeMillis())
            })
        }

    suspend fun deleteAsk(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("ai_ask", "id=?", arrayOf(id.toString()))
        Unit
    }
}

/**
 * 拼 forms 的 LIKE 模式
 *
 * forms 存的是 "a,b,c"，匹配时要两端都补逗号
 * 否则单个词的 forms（没有任何逗号）永远匹配不上，
 * 这正是收藏了却取消不掉的第一个成因
 *
 * 放在类外是为了单测能直接调
 */
internal fun formLikePattern(word: String): String = "%,${word.trim().lowercase()},%"
