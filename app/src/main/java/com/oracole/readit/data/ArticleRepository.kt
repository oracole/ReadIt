package com.oracole.readit.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文章仓库
 *
 * 磁盘布局：
 *
 *   files/articles/meta.json    元数据数组，不含正文        几十 KB，经常写
 *   files/articles/<id>.txt     每篇正文                    导入时写一次，之后只读
 *
 * 为什么拆开：
 *
 * 原来是所有文章（含全部正文）存在一个 articles.json 里，每次保存都全量重写
 *   收藏只是把一个布尔值从 false 改成 true，却要把几万字的正文重新序列化、
 * 写盘、再复制一份 .bak，长文下点一下星标要卡零点几秒
 *
 *   正文是不可变的：导入之后没有任何代码修改它（全项目只有 add 和反序列化两处
 * 给 content 赋值）。既然不会变，就不该跟着会变的元数据一起重写
 *
 * 拆开之后，收藏 / 进度 / 改名 / 标注只碰 meta.json（几十 KB）
 * 正文文件一个字节都不动
 *
 * 内存里的 Article 结构没有变，content 照旧带着全文，所以 UI 层零改动
 */
class ArticleRepository(
    private val ctx: Context,
    /** 仅供单元测试注入临时目录。生产环境传 null，用 ctx.filesDir */
    baseDir: File? = null
) {

    private val root: File = baseDir ?: ctx.filesDir

    private val dir: File get() = File(root, "articles")
    private val metaFile: File get() = File(dir, "meta.json")

    /** 老版本的单文件仓库。只在迁移时用到。 */
    private val legacyFile: File get() = File(root, "articles.json")

    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles: StateFlow<List<Article>> = _articles.asStateFlow()

    /**
     * 上次加载是否失败
     *
     * 文件损坏时不再静默当成空库，那会让用户以为文章丢了
     * 接着导入一篇新的，于是保存把原文件覆盖成只剩这一篇
     * 上层据此提示，并禁止在加载失败时写入
     */
    @Volatile
    var lastLoadFailed: Boolean = false
        private set

    /** 是否把损坏的旧文件留了档。上层提示一次后清掉。 */
    @Volatile
    var corruptedBackupKept: Boolean = false

    /** 上一次保存是否失败。上层据此提示，避免「界面说成功、重启后没了」。 */
    @Volatile
    var lastSaveFailed: Boolean = false
        private set

    /** 串行化写入。定时保存、onDispose、收藏切换会并发进来，共用同一个 tmp 会互相踩。 */
    private val saveLock = Any()

    /**
     * 打日志。单元测试里 android.util.Log 未实现，调用会抛异常
     * 所以包一层，日志永远不该让业务流程崩掉
     */
    private fun logE(msg: String, e: Throwable? = null) {
        runCatching { logE(msg, e) }
    }

    private fun logI(msg: String) {
        runCatching { logI(msg) }
    }

    // ---------------------------------------------------------------- 加载

    suspend fun load() = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val migrateFailed = migrateLegacyIfNeeded()

        if (!metaFile.exists()) {
            _articles.value = emptyList()
            // 老文件存在但解析不了：必须标记失败并保留原文件
            // 当成空库的话，用户会以为文章全没了，接着导入新的
            // persist 就会把那个还完好的老文件覆盖掉，真正的数据丢失
            lastLoadFailed = migrateFailed
            return@withContext
        }

        val parsed = runCatching { readAll() }
        parsed.fold(
            onSuccess = {
                _articles.value = it
                lastLoadFailed = false
            },
            onFailure = {
                logE("加载失败", it)
                lastLoadFailed = true
                _articles.value = emptyList()
            }
        )
    }

    /** 读 meta，再逐篇读正文。正文缺失的条目跳过并记日志。 */
    private fun readAll(): List<Article> {
        val arr = JSONArray(metaFile.readText())
        val list = ArrayList<Article>(arr.length())
        var missing = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getLong("id")
            val txt = contentFile(id)
            if (!txt.exists()) {
                // 正文没了，这条实际上已经不存在
                // 保留一条空白条目只会让书架里多出一篇点进去空白的文章，更困惑
                missing++
                logE("缺少正文文件，跳过文章 $id")
                continue
            }
            list.add(o.toArticle(txt.readText()))
        }
        if (missing > 0) {
            logE("本次加载跳过 $missing 篇（正文缺失）")
        }
        return list.sortedByDescending { it.lastReadAt ?: it.createdAt }
    }

    // ---------------------------------------------------------------- 写操作

    suspend fun add(
        title: String,
        content: String,
        sourceName: String?,
        targetWords: List<String>? = null
    ): Article =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // id 现在就是文件名。同一毫秒导入两篇会撞，撞了后写的会覆盖先写的
            // 等于文章丢失。所以撞了就往后找
            val existing = _articles.value
            var id = now
            while (existing.any { it.id == id } || contentFile(id).exists()) id++

            val article = Article(
                id = id,
                title = title.ifBlank { deriveTitle(content) },
                content = content,
                charCount = content.length,
                wordCount = countWords(content),
                sourceName = sourceName,
                createdAt = now,
                targetWords = targetWords?.takeIf { it.isNotEmpty() }?.joinToString(","),
                highlightTargets = !targetWords.isNullOrEmpty()
            )
            val list = existing + article

            // 先写正文，再写 meta
            // 这个顺序只会产生孤儿 txt（多一个没人引用的文件，无害）
            // 反过来会产生meta 指向不存在的 txt（书架里点进去空白）
            if (!writeContent(id, content)) {
                throw IllegalStateException("保存失败，存储空间可能不足")
            }
            // 存不下就不要往内存里放。否则界面显示已导入、重启后消失
            // 用户以为成功了，实际什么都没留下
            if (!persist(list)) {
                contentFile(id).delete()   // 回滚刚写的正文
                throw IllegalStateException("保存失败，存储空间可能不足")
            }
            _articles.value = list.sortedByDescending { it.lastReadAt ?: it.createdAt }
            article
        }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val list = _articles.value.filterNot { it.id == id }
        // 先写 meta 再删正文：反过来会出现 meta 里已经没有、但文件还在，那只是孤儿
        // 先删正文的话，meta 写失败就成了悬空引用
        persist(list)
        _articles.value = list
        contentFile(id).delete()
    }

    /** 批量删除 */
    suspend fun deleteBatch(ids: Set<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val list = _articles.value.filterNot { it.id in ids }
        persist(list)
        _articles.value = list
        ids.forEach { contentFile(it).delete() }
    }

    // ---- 以下五个只改元数据，不碰正文 ----

    /** 保存阅读位置。offset 为字符偏移，progress>=0.95 视为读完 */
    suspend fun saveProgress(id: Long, offset: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val list = _articles.value.map { a ->
            if (a.id != id) a
            else {
                val p = if (a.charCount <= 0) 0f else offset.toFloat() / a.charCount
                a.copy(
                    progressOffset = offset.coerceIn(0, a.charCount),
                    lastReadAt = now,
                    isFinished = a.isFinished || p >= 0.95f,
                    finishedAt = if (a.isFinished || p >= 0.95f) (a.finishedAt ?: now) else a.finishedAt
                )
            }
        }
        _articles.value = list
        persist(list)
    }

    suspend fun toggleFavorite(id: Long) = withContext(Dispatchers.IO) {
        val list = _articles.value.map { if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it }
        // 先更新内存，界面立刻响应；写盘放到后面
        // 收藏丢了重来一次即可，不值得让星标等磁盘
        _articles.value = list
        persist(list)
    }

    /** 批量设置收藏状态 */
    suspend fun setFavoriteBatch(ids: Set<Long>, favorite: Boolean) =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext
            val list = _articles.value.map {
                if (it.id in ids) it.copy(isFavorite = favorite) else it
            }
            _articles.value = list
            persist(list)
        }

    /** 切换某篇文章的目标词标注 */
    suspend fun setHighlightTargets(id: Long, on: Boolean) = withContext(Dispatchers.IO) {
        val list = _articles.value.map {
            if (it.id == id) it.copy(highlightTargets = on) else it
        }
        _articles.value = list
        persist(list)
    }

    suspend fun rename(id: Long, title: String) = withContext(Dispatchers.IO) {
        val list = _articles.value.map { if (it.id == id) it.copy(title = title) else it }
        _articles.value = list
        persist(list)
    }

    // ---------------------------------------------------------------- 正文文件

    private fun contentFile(id: Long) = File(dir, "$id.txt")

    private fun writeContent(id: Long, content: String): Boolean {
        // 目录必须在这里确保存在
        // add() 是先写正文再写 meta 的，而 mkdirs 原本只在 persist() 里，
        // 首次导入时目录还不存在，正文会写失败，整篇文章导入不进来
        dir.mkdirs()
        val target = contentFile(id)
        val tmp = File(dir, "$id.txt.tmp")
        return try {
            tmp.writeText(content)
            if (tmp.renameTo(target)) {
                true
            } else {
                val ok = runCatching { target.writeText(content) }.isSuccess
                tmp.delete()
                ok
            }
        } catch (e: Exception) {
            logE("写正文失败 id=$id", e)
            tmp.delete()
            false
        }
    }

    // ---------------------------------------------------------------- 写 meta

    /**
     * 原子写入 meta.json：先写临时文件，再替换
     *
     * 几处容易写错的地方，都是上一版踩过的：
     *
     * .bak 存的是替换前的旧内容，必须在 rename 之前拷
     * 上一版在 rename 之后拷，存下来的其实是新内容，等于没有备份
     *
     * rename 失败时不能先删原文件。上一版 delete 之后 rename 又失败
     * 结果原文件没了、新文件也没写进去，兜底路径自己制造了要防的后果
     *
     * 并发要串行化。定时保存和退出保存同时进来会互相覆盖同一个 tmp
     */
    private fun persist(list: List<Article>): Boolean = synchronized(saveLock) {
        dir.mkdirs()
        val arr = JSONArray()
        list.forEach { arr.put(it.toMetaJson()) }
        val text = arr.toString()

        // 上次加载失败：先把损坏的文件挪走留档，再允许写入
        // 一直拒绝写入会让整个会话的保存全部静默丢失，那是另一种数据丢失
        if (lastLoadFailed) {
            runCatching {
                val stamp = System.currentTimeMillis()
                metaFile.copyTo(File(dir, "meta.json.corrupt-$stamp"), overwrite = true)
            }
            lastLoadFailed = false
            corruptedBackupKept = true
        }

        val tmp = File(dir, "meta.json.tmp")
        val bak = File(dir, "meta.json.bak")
        val ok = try {
            tmp.writeText(text)

            // 备份旧内容，必须在替换之前
            if (metaFile.exists()) runCatching { metaFile.copyTo(bak, overwrite = true) }

            // rename 在同一文件系统上是原子的
            if (tmp.renameTo(metaFile)) {
                true
            } else {
                // rename 失败（存储满、权限异常）：原文件还在，直接覆盖写
                // 此时 .bak 里已经有旧内容，最坏情况还能手工恢复
                val r = runCatching { metaFile.writeText(text) }
                tmp.delete()
                r.isSuccess
            }
        } catch (e: Exception) {
            logE("保存 meta 失败", e)
            tmp.delete()
            false
        }

        lastSaveFailed = !ok
        if (!ok) {
            logE("保存失败，内存与磁盘可能不一致")
        }
        ok
    }

    // ---------------------------------------------------------------- 迁移

    /**
     * 老版本所有内容存在 files/articles.json 一个文件里，含全文
     * 首次启动时拆成 meta.json + 若干 <id>.txt
     *
     * 每一步都可能失败，所以：
     *   - 任何一个 txt 写失败 → 删掉已写的，原文件不动，下次启动重试
     *   - 校验不过 → 同上
     *   - 只有全部校验通过才把老文件改名归档
     *
     * 老文件改名成 articles.json.migrated 而不删除：万一拆出来的东西有问题
     * 用户还能靠它恢复。占的那点空间换一份保险，值得
     *
     * 幂等：meta.json 存在就不迁移，所以重复调用不会重复做
     */
    private fun migrateLegacyIfNeeded(): Boolean {
        if (metaFile.exists()) return false
        if (!legacyFile.exists()) return false

        val parsed = runCatching {
            val arr = JSONArray(legacyFile.readText())
            ArrayList<Article>(arr.length()).apply {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(o.toArticle(o.getString("content")))
                }
            }
        }.getOrElse {
            // 老文件解析不了：不迁、不改，返回 true 让上层标记加载失败
            logE("老格式解析失败，跳过迁移", it)
            return true
        }

        if (parsed.isEmpty()) {
            runCatching { legacyFile.renameTo(File(root, "articles.json.migrated")) }
            return false
        }

        val written = ArrayList<Long>(parsed.size)
        fun rollback() {
            written.forEach { contentFile(it).delete() }
            metaFile.delete()
            File(dir, "meta.json.tmp").delete()
            File(dir, "meta.json.bak").delete()
        }

        // 1. 逐篇写正文
        for (a in parsed) {
            if (!writeContent(a.id, a.content)) {
                rollback()
                logE("迁移中断：写正文失败 id=${a.id}")
                return true
            }
            written.add(a.id)
        }

        // 2. 写 meta
        if (!persist(parsed)) {
            rollback()
            logE("迁移中断：写 meta 失败")
            return true
        }

        // 3. 校验：条数、每篇正文长度、首尾抽样
        val ok = runCatching {
            val arr = JSONArray(metaFile.readText())
            if (arr.length() != parsed.size) return@runCatching false
            parsed.all { a ->
                val f = contentFile(a.id)
                if (!f.exists()) return@all false
                val text = f.readText()
                if (text.length != a.charCount) return@all false
                val head = minOf(100, text.length)
                text.take(head) == a.content.take(head) &&
                    text.takeLast(head) == a.content.takeLast(head)
            }
        }.getOrDefault(false)

        if (!ok) {
            rollback()
            logE("迁移校验不通过，已回滚")
            return false
        }

        // 4. 归档老文件（不删）
        val archived = File(root, "articles.json.migrated")
        if (!legacyFile.renameTo(archived)) {
            runCatching { legacyFile.copyTo(archived, overwrite = true) }
        }
        logI("迁移完成，${parsed.size} 篇")
        return false
    }

    // ---------------------------------------------------------------- JSON

    /**
     * 元数据。不含 content，正文在 <id>.txt 里
     * 这条是整个改动的核心，往这里加 content 就等于前功尽弃
     */
    private fun Article.toMetaJson() = JSONObject().apply {
        put("id", id); put("title", title)
        put("charCount", charCount); put("wordCount", wordCount)
        put("progressOffset", progressOffset); put("isFavorite", isFavorite)
        put("isFinished", isFinished); put("sourceName", sourceName ?: JSONObject.NULL)
        put("createdAt", createdAt); put("lastReadAt", lastReadAt ?: JSONObject.NULL)
        put("finishedAt", finishedAt ?: JSONObject.NULL)
        put("targetWords", targetWords ?: JSONObject.NULL)
        put("highlightTargets", highlightTargets)
    }

    private fun JSONObject.toArticle(content: String) = Article(
        id = getLong("id"),
        title = getString("title"),
        content = content,
        charCount = optInt("charCount"),
        wordCount = optInt("wordCount"),
        progressOffset = optInt("progressOffset"),
        isFavorite = optBoolean("isFavorite"),
        isFinished = optBoolean("isFinished"),
        sourceName = if (isNull("sourceName")) null else getString("sourceName"),
        createdAt = getLong("createdAt"),
        lastReadAt = if (isNull("lastReadAt")) null else getLong("lastReadAt"),
        finishedAt = if (isNull("finishedAt")) null else getLong("finishedAt"),
        targetWords = if (isNull("targetWords")) null else optString("targetWords"),
        highlightTargets = optBoolean("highlightTargets", false)
    )
}
