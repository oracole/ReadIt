package com.oracole.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「用户点的词属于哪条收藏记录」的判定。
 *
 * 背景：打开「按原形收藏」后，记录里的 word 可能是原形
 * （收藏 means 存成 mean），而用户在正文里点的永远是 means 这种形态。
 * 所有定位记录的地方都必须走 matches()，不能只用 word 比较。
 */
class VocabMatchTest {

    private fun item(word: String, forms: String? = null) = VocabItem(
        id = 1, word = word, displayWord = word, phonetic = null,
        translation = "x", status = 0, createdAt = 0, updatedAt = 0,
        knownAt = null, forms = forms
    )

    @Test
    fun `按原词匹配`() {
        assertTrue(item("mean", "means").matches("mean"))
    }

    @Test
    fun `按形态匹配`() {
        assertTrue(item("mean", "means").matches("means"))
    }

    @Test
    fun `多个形态都能匹配`() {
        val it0 = item("go", "went,goes,going")
        listOf("go", "went", "goes", "going").forEach {
            assertTrue("$it 应匹配", it0.matches(it))
        }
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(item("mean", "means").matches("Means"))
        assertTrue(item("mean", "means").matches("MEAN"))
    }

    @Test
    fun `前后空格被忽略`() {
        assertTrue(item("mean", "means").matches("  means  "))
    }

    @Test
    fun `不相关的不匹配`() {
        assertFalse(item("mean", "means").matches("meaning"))
        assertFalse(item("mean", "means").matches("me"))
        assertFalse(item("mean", "means").matches(""))
    }

    @Test
    fun `没有 forms 时只认原词`() {
        assertTrue(item("run").matches("run"))
        assertFalse(item("run").matches("runs"))
    }

    @Test
    fun `forms 里和原词相同的项不会误判`() {
        // formList 会过滤掉和 displayWord 相同的项，避免重复
        assertEquals(listOf("means"), item("mean", "mean,means").formList)
        assertTrue(item("mean", "mean,means").matches("means"))
    }

    @Test
    fun `像模式两端都补了逗号`() {
        // 只补开头的话，forms 里只有单个词时永远匹配不上
        assertEquals("%,means,%", formLikePattern("means"))
        assertEquals("%,means,%", formLikePattern("  Means "))
    }

    @Test
    fun `像模式能命中单形态`() {
        // 模拟 SQL: (',' || forms || ',') LIKE '%,means,%'
        val pattern = formLikePattern("means")
        fun sqlMatch(forms: String): Boolean =
            ",${forms.lowercase().replace(" ", "")},".contains(
                pattern.removePrefix("%").removeSuffix("%")
            )
        assertTrue(sqlMatch("means"))
        assertTrue(sqlMatch("mean, means"))
        assertTrue(sqlMatch("means,mean"))
        assertFalse(sqlMatch("meaning"))
        assertFalse(sqlMatch("mean"))
    }
    @Test
    fun `同一形态对应两条记录时 base 决定归属`() {
        // found 可能同时存在：find（forms 含 found）和独立的 found
        val asForm = item("find", "found")      // id 都是 1，这里只验语义
        val standalone = VocabItem(
            id = 2, word = "found", displayWord = "found", phonetic = null,
            translation = "建立", status = 0, createdAt = 0, updatedAt = 0, knownAt = null
        )
        // 不给 base：两条都能匹配，取列表里靠前的那个（不可靠）
        assertTrue(asForm.matches("found"))
        assertTrue(standalone.matches("found"))

        // 给 base：只有一条命中
        val list = listOf(asForm, standalone)
        fun pick(word: String, base: String?) =
            base?.let { b -> list.firstOrNull { it.word.equals(b, true) } }
                ?: list.firstOrNull { it.matches(word) }

        assertEquals("find", pick("found", "find")?.word)
        assertEquals("found", pick("found", "found")?.word)
        assertEquals("find", pick("found", null)?.word)   // 退化：取第一条
    }
}
