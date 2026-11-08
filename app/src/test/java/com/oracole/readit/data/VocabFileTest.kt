package com.oracole.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用真实文件回归。
 *
 * 样例放在 src/test/resources/vocab/，和手工测试用的那批完全一致。
 * 走的是「字节 → 解码 → 解析」全链路（跳过 Android 的 ContentResolver 部分）。
 */
class VocabFileTest {

    private fun load(name: String): VocabParseResult.Ok {
        val bytes = File("src/test/resources/vocab/$name").readBytes()
        val r = VocabListImporter.parseBytesForTest(bytes, name)
        assertTrue("$name 应解析成功，实际 $r", r is VocabParseResult.Ok)
        return r as VocabParseResult.Ok
    }

    @Test
    fun `01 标准两列`() {
        val r = load("01_basic.csv")
        assertEquals(5, r.entries.size)
        assertEquals("UTF-8", r.encoding)
        assertEquals("逗号", r.delimiter)
        assertEquals(
            listOf("abandon", "keen", "florid", "ubiquitous", "petrichor"),
            r.entries.map { it.word }
        )
    }

    @Test
    fun `02 混乱 CSV`() {
        val r = load("02_messy.csv")
        assertEquals("UTF-8（BOM）", r.encoding)
        assertEquals(6, r.entries.size)
        // 表头被跳过
        assertTrue(r.entries.none { it.word.equals("word", true) })
        // 引号内的逗号保留
        assertTrue(r.entries.any { it.word == "SENTENCE, WITH COMMA" })
        // 重复的 run 只留一条
        assertEquals(1, r.entries.count { it.word.equals("run", true) })
        // 前后空格被去掉
        assertTrue(r.entries.any { it.word == "spaced" })
        assertEquals(4, r.badLines.size)
    }

    @Test
    fun `03 Anki 导出`() {
        val r = load("03_tab_anki.txt")
        assertEquals(5, r.entries.size)
        assertEquals("制表符", r.delimiter)
        assertEquals("放弃", r.entries.first().meaning)
    }

    @Test
    fun `04 一行一个词`() {
        val r = load("04_plain.txt")
        assertEquals(10, r.entries.size)
        assertTrue(r.entries.all { it.meaning.isEmpty() })
    }

    @Test
    fun `05 分号分隔`() {
        val r = load("05_semicolon.csv")
        assertEquals(3, r.entries.size)
        assertEquals("分号", r.delimiter)
    }

    @Test
    fun `06 GBK 编码`() {
        val r = load("06_gbk_gbk.csv")
        assertEquals("GBK", r.encoding)
        assertEquals(2, r.entries.size)
        assertEquals("放弃", r.entries.first().meaning)
    }

    @Test
    fun `07 混合来源`() {
        val r = load("07_mixed_sources.csv")
        assertEquals(7, r.entries.size)
        assertTrue(r.entries.none { it.word.equals("word", true) })
        assertTrue(r.entries.all { it.meaning.isNotBlank() })
    }

    @Test
    fun `08 全部无释义`() {
        val r = load("08_no_meaning_rare.txt")
        assertEquals(15, r.entries.size)
        assertTrue(r.entries.all { it.meaning.isEmpty() })
    }

    @Test
    fun `09 全部已收录的短表`() {
        val r = load("09_all_covered.csv")
        assertEquals(4, r.entries.size)
    }

    @Test
    fun `10 边界行`() {
        val r = load("10_edge.txt")
        assertEquals(5, r.entries.size)
        assertEquals(3, r.badLines.size)
        assertTrue(r.entries.any { it.word == "this-is-a-valid-compound" })
        assertTrue(r.entries.any { it.word == "O'Brien" })
    }
}
