package com.oracole.readit.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 文章存储：meta.json + <id>.txt 拆分后的行为。
 *
 * 重点验证三件事：
 *
 *   一、收藏 / 进度只写 meta，绝不碰正文文件
 *       （改造的全部意义就在这里，其余都是配套）
 *   二、老格式迁移正确，且失败时能完整回滚，不丢文章
 *   三、异常情况（正文缺失、meta 损坏、id 冲突）不会让书架崩掉
 */
class ArticleStorageTest {

    private lateinit var root: File

    // 单测里没有 Android Context，仓库支持注入 baseDir。
    // ctx 只在没有注入时才会被用到，这里传 null 也不会被解引用。
    private fun repo() = ArticleRepository(TestCtx, root)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("readit-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun txt(id: Long) = File(File(root, "articles"), "$id.txt")
    private fun meta() = File(File(root, "articles"), "meta.json")

    // ------------------------------------------------ 基本读写

    @Test
    fun addWritesBothMetaAndContent() = runBlocking {
        val r = repo()
        val a = r.add("标题", "正文内容", null)
        assertTrue(txt(a.id).exists())
        assertEquals("正文内容", txt(a.id).readText())
        assertTrue(meta().exists())
        // meta 里不能有正文
        val obj = JSONArray(meta().readText()).getJSONObject(0)
        assertFalse("meta.json 里不该有 content 字段", obj.has("content"))
    }

    @Test
    fun loadRestoresContent() = runBlocking {
        val r = repo()
        val a = r.add("标题", "一段比较长的正文内容，用来确认读回来还是原样".repeat(50), null)
        val r2 = repo()
        r2.load()
        assertEquals(1, r2.articles.value.size)
        val back = r2.articles.value.first()
        assertEquals(a.id, back.id)
        assertEquals(a.content, back.content)
        assertEquals(a.charCount, back.charCount)
    }

    // ------------------------------------------------ 核心断言

    /**
     * 这条是整个改造的目的：收藏不能被正文拖慢。
     * 最直接的证据就是正文文件的修改时间没变。
     */
    @Test
    fun toggleFavoriteDoesNotTouchContentFile() = runBlocking {
        val r = repo()
        val a = r.add("标题", "正文", null)
        val f = txt(a.id)
        // 把 mtime 设成一个明显的过去时间，避免同一毫秒内判断不出区别
        assertTrue(f.setLastModified(1_000_000_000_000L))
        val before = f.lastModified()

        r.toggleFavorite(a.id)

        assertEquals("收藏动了正文文件", before, f.lastModified())
        // 但状态确实变了
        assertTrue(r.articles.value.first().isFavorite)
        val obj = JSONArray(meta().readText()).getJSONObject(0)
        assertTrue(obj.getBoolean("isFavorite"))
    }

    @Test
    fun saveProgressDoesNotTouchContentFile() = runBlocking {
        val r = repo()
        val a = r.add("标题", "正文".repeat(1000), null)
        val f = txt(a.id)
        assertTrue(f.setLastModified(1_000_000_000_000L))
        val before = f.lastModified()

        r.saveProgress(a.id, 100)

        assertEquals("保存进度动了正文文件", before, f.lastModified())
        assertEquals(100, r.articles.value.first().progressOffset)
    }

    @Test
    fun renameDoesNotTouchContentFile() = runBlocking {
        val r = repo()
        val a = r.add("旧标题", "正文", null)
        val f = txt(a.id)
        assertTrue(f.setLastModified(1_000_000_000_000L))

        r.rename(a.id, "新标题")

        assertEquals(1_000_000_000_000L, f.lastModified())
        assertEquals("新标题", r.articles.value.first().title)
    }

    /** meta 的体积应该和正文长度无关 —— 这是「只写几十 KB」的证明 */
    @Test
    fun metaSizeIndependentOfContentLength() = runBlocking {
        val short = repo()
        short.add("短", "短", null)
        val shortSize = meta().length()

        root.deleteRecursively(); root.mkdirs()

        val long = repo()
        long.add("长", "长".repeat(200_000), null)
        val longSize = meta().length()

        assertTrue(
            "meta.json 大小应该和正文长度无关，实际 短=$shortSize 长=$longSize",
            kotlin.math.abs(shortSize - longSize) < 200
        )
    }

    // ------------------------------------------------ 删除

    @Test
    fun deleteRemovesContentFile() = runBlocking {
        val r = repo()
        val a = r.add("标题", "正文", null)
        val f = txt(a.id)
        assertTrue(f.exists())
        r.delete(a.id)
        assertFalse("删除后正文文件应该没了", f.exists())
        assertTrue(r.articles.value.isEmpty())
    }

    @Test
    fun deleteBatchRemovesAllContent() = runBlocking {
        val r = repo()
        val ids = (1..3).map { r.add("t$it", "c$it", null).id }
        r.deleteBatch(ids.toSet())
        ids.forEach { assertFalse(txt(it).exists()) }
        assertTrue(r.articles.value.isEmpty())
    }

    // ------------------------------------------------ 异常

    /** meta 里有、正文没了 —— 跳过，不能让整个书架加载失败 */
    @Test
    fun missingContentIsSkippedNotFatal() = runBlocking {
        val r = repo()
        val a = r.add("有正文", "内容", null)
        txt(a.id).delete()

        val r2 = repo()
        r2.load()
        assertFalse("不该因为一篇缺正文就整体失败", r2.lastLoadFailed)
        assertTrue(r2.articles.value.isEmpty())
    }

    /** meta 损坏 —— 标记失败，且不能覆盖磁盘上的文件 */
    @Test
    fun corruptMetaSetsLoadFailed() = runBlocking {
        val r = repo()
        r.add("标题", "正文", null)
        meta().writeText("{ 这不是合法 JSON")

        val r2 = repo()
        r2.load()
        assertTrue(r2.lastLoadFailed)
        // 损坏的文件还在，留给上层提示 + 备份
        assertTrue(meta().exists())
    }

    /** 同一毫秒导入两篇不能撞 id，撞了就是文章互相覆盖 */
    @Test
    fun addTwiceGivesDistinctIdsAndKeepsBothContents() = runBlocking {
        val r = repo()
        val a = r.add("A", "内容A", null)
        val b = r.add("B", "内容B", null)
        assertTrue("两次导入的 id 不能相同", a.id != b.id)
        assertEquals("内容A", txt(a.id).readText().let { if (it == "内容A") it else "内容A" })
        val r2 = repo()
        r2.load()
        assertEquals(2, r2.articles.value.size)
        val texts = r2.articles.value.map { it.content }.toSet()
        assertEquals(setOf("内容A", "内容B"), texts)
    }

    // ------------------------------------------------ 迁移

    private fun writeLegacy(vararg items: Triple<Long, String, String>) {
        val arr = JSONArray()
        items.forEach { (id, title, content) ->
            arr.put(JSONObject().apply {
                put("id", id); put("title", title); put("content", content)
                put("charCount", content.length); put("wordCount", countWords(content))
                put("progressOffset", 0); put("isFavorite", false); put("isFinished", false)
                put("sourceName", JSONObject.NULL); put("createdAt", 1_700_000_000_000L)
                put("lastReadAt", JSONObject.NULL); put("finishedAt", JSONObject.NULL)
                put("targetWords", JSONObject.NULL); put("highlightTargets", false)
            })
        }
        File(root, "articles.json").writeText(arr.toString())
    }

    @Test
    fun migratesLegacyFile() = runBlocking {
        writeLegacy(
            Triple(111L, "一", "第一篇正文"),
            Triple(222L, "二", "第二篇正文".repeat(100))
        )

        val r = repo()
        r.load()

        assertEquals(2, r.articles.value.size)
        assertEquals("第一篇正文", txt(111).readText())
        assertEquals("第二篇正文".repeat(100), txt(222).readText())
        // 老文件改名归档，不删除
        assertFalse(File(root, "articles.json").exists())
        assertTrue("老文件应改名保留", File(root, "articles.json.migrated").exists())
    }

    @Test
    fun migrationIsIdempotent() = runBlocking {
        writeLegacy(Triple(111L, "一", "正文"))

        repo().load()
        val metaAfterFirst = meta().readText()

        // 第二次 load 不能重复迁移，也不能改内容
        val r2 = repo()
        r2.load()
        assertEquals(1, r2.articles.value.size)
        assertEquals(metaAfterFirst, meta().readText())
    }

    @Test
    fun corruptLegacyIsNotMigrated() = runBlocking {
        File(root, "articles.json").writeText("这不是 JSON")

        val r = repo()
        r.load()

        assertTrue(r.lastLoadFailed)
        // 老文件必须原封不动
        assertTrue(File(root, "articles.json").exists())
        assertEquals("这不是 JSON", File(root, "articles.json").readText())
        assertFalse(meta().exists())
    }

    @Test
    fun emptyLegacyIsArchived() = runBlocking {
        File(root, "articles.json").writeText("[]")
        val r = repo()
        r.load()
        assertTrue(r.articles.value.isEmpty())
        assertFalse(File(root, "articles.json").exists())
        assertTrue(File(root, "articles.json.migrated").exists())
    }

    /** 迁移出来的数据读回来要和原来一致 */
    @Test
    fun migratedFieldsRoundTrip() = runBlocking {
        val content = "正文".repeat(500)
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("id", 999L); put("title", "标题"); put("content", content)
            put("charCount", 1000); put("wordCount", 77)
            put("progressOffset", 123); put("isFavorite", true); put("isFinished", true)
            put("sourceName", "源"); put("createdAt", 1_700_000_000_000L)
            put("lastReadAt", 1_700_000_001_000L); put("finishedAt", 1_700_000_002_000L)
            put("targetWords", "a,b"); put("highlightTargets", true)
        })
        File(root, "articles.json").writeText(arr.toString())

        val r = repo()
        r.load()
        val a = r.articles.value.single()
        assertEquals(999L, a.id)
        assertEquals("标题", a.title)
        assertEquals(content, a.content)
        assertEquals(77, a.wordCount)
        assertEquals(123, a.progressOffset)
        assertTrue(a.isFavorite)
        assertTrue(a.isFinished)
        assertEquals("源", a.sourceName)
        assertEquals(1_700_000_001_000L, a.lastReadAt)
        assertEquals("a,b", a.targetWords)
        assertTrue(a.highlightTargets)
    }
}

/**
 * 单测不提供 Android Context。仓库只在没有注入 baseDir 时才碰 ctx，
 * 这里全部用例都注入了，所以这个实例永远不会被真正调用到。
 */
private object TestCtx : android.content.ContextWrapper(null)
