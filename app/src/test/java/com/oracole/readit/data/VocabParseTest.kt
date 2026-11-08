package com.oracole.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器回归。
 *
 * 样例就是从 testdata/vocab 里抄过来的形状，
 * 覆盖：表头、BOM、引号内逗号、Anki 头、分号、整行、重复、非法词。
 */
class VocabParseTest {

    private fun p(text: String): VocabParseResult =
        VocabListImporter.parseForTest(text, "t.txt", "UTF-8")

    private fun ok(text: String): VocabParseResult.Ok {
        val r = p(text)
        assertTrue("应当解析成功，实际是 $r", r is VocabParseResult.Ok)
        return r as VocabParseResult.Ok
    }

    @Test
    fun `标准两列 CSV`() {
        val r = ok("abandon,放弃\nkeen,热衷的\n")
        assertEquals(2, r.entries.size)
        assertEquals("abandon", r.entries[0].word)
        assertEquals("放弃", r.entries[0].meaning)
        assertEquals("逗号", r.delimiter)
    }

    @Test
    fun `表头被跳过`() {
        val r = ok("word,meaning,note\nabandon,放弃,四级\n")
        assertEquals(1, r.entries.size)
        assertEquals("abandon", r.entries[0].word)
    }

    @Test
    fun `中文表头也跳过`() {
        val r = ok("单词,释义\nabandon,放弃\n")
        assertEquals(1, r.entries.size)
        assertEquals("abandon", r.entries[0].word)
    }

    @Test
    fun `引号内的逗号不当分隔符`() {
        val r = ok("\"SENTENCE, WITH COMMA\",含逗号的行\n")
        assertEquals(1, r.entries.size)
        assertEquals("SENTENCE, WITH COMMA", r.entries[0].word)
        assertEquals("含逗号的行", r.entries[0].meaning)
    }

    @Test
    fun `Anki 的 separator 头优先`() {
        val r = ok("#separator:tab\n#html:false\nabandon\t放弃\n")
        assertEquals(1, r.entries.size)
        assertEquals("制表符", r.delimiter)
    }

    @Test
    fun `分号分隔`() {
        val r = ok("abandon;放弃\ngossamer;轻薄\n")
        assertEquals(2, r.entries.size)
        assertEquals("分号", r.delimiter)
    }

    @Test
    fun `一行一个词`() {
        val r = ok("abandon\nkeen\nluminous\n")
        assertEquals(3, r.entries.size)
        assertTrue(r.entries.all { it.meaning.isEmpty() })
    }

    @Test
    fun `重复词只保留一条`() {
        val r = ok("abandon\nabandon\nAbandon\n")
        assertEquals(1, r.entries.size)
    }

    @Test
    fun `非法词被剔除`() {
        val r = ok("abandon\n12345\n!!!\n这个词条是中文\n\n")
        assertEquals(1, r.entries.size)
        // 空行在收集阶段就跳过了，不进 badLines
        assertEquals(3, r.badLines.size)
        assertTrue(r.badLines.any { it.contains("含中文") })
        assertTrue(r.badLines.any { it.contains("不含字母") })
    }

    @Test
    fun `单个长单词仍有效`() {
        // 连字符和撇号是合法的，不能当成异常符号
        val r = ok("this-is-a-valid-compound\nO'Brien\ne.g.\n")
        assertEquals(3, r.entries.size)
    }

    @Test
    fun `BOM 在读取层去掉，解析层不感知`() {
        val r = ok("abandon,放弃\n")
        assertEquals("abandon", r.entries[0].word)
    }

    @Test
    fun `全空文件报错`() {
        val r = p("\n\n   \n")
        assertTrue(r is VocabParseResult.Err)
    }

    @Test
    fun `真 BOM 文件（含 U+FEFF 开头）表头仍识别`() {
        val r = ok("\uFEFFword,meaning\nabandon,放弃\n")
        // BOM 若未被剥掉，首列会变成 "\uFEFFword"，表头识别失败，会多出一条错词
        assertEquals(1, r.entries.size)
        assertEquals("abandon", r.entries[0].word)
    }
    @Test
    fun `粘贴文本走同一套解析`() {
        val r = VocabListImporter.parseText("abandon,放弃\nkeen,热衷的\n")
        assertTrue(r is VocabParseResult.Ok)
        val o = r as VocabParseResult.Ok
        assertEquals(2, o.entries.size)
        assertEquals(VocabListImporter.PASTED_LABEL, o.fileName)
        // 粘贴的文本没有编码信息，留空以便界面过滤
        assertEquals("", o.encoding)
    }

    @Test
    fun `粘贴文本的表头同样跳过`() {
        val r = VocabListImporter.parseText("word,meaning\nabandon,放弃\n")
        assertEquals(1, (r as VocabParseResult.Ok).entries.size)
    }

    @Test
    fun `粘贴纯空白报错`() {
        assertTrue(VocabListImporter.parseText("   \n\n  \n") is VocabParseResult.Err)
    }
}
