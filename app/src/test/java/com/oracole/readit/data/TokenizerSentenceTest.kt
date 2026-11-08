package com.oracole.readit.data

import com.oracole.readit.ui.reader.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 句界判定。用例来自实际会出错的形状。
 *
 * 判定规则在 Tokenizer.isBreakAtIn 里，通过 sentenceRange 观察行为。
 */
class TokenizerSentenceTest {

    /** 取包含 [word] 的那一句原文（首尾去空白） */
    private fun sentenceOf(text: String, word: String): String {
        val at = text.indexOf(word)
        assertTrue("找不到 $word", at >= 0)
        val (s, e) = Tokenizer.sentenceRange(text, at, at + word.length)
        return text.substring(s, e).trim()
    }

    // ---- 省略号 ----

    @Test
    fun `省略号后接大写应断句`() {
        // 原来三个点全部 return false，两句被合成一句
        val t = "I don't know... Maybe later."
        assertEquals("Maybe later.", sentenceOf(t, "later"))
    }

    @Test
    fun `省略号后接小写不断句`() {
        val t = "Wait... what happened?"
        assertEquals("Wait... what happened?", sentenceOf(t, "what"))
    }

    // ---- 引号包裹的缩写 ----

    @Test
    fun `引号内的称谓不断句`() {
        // 原来 before 是 "Mr，不在缩写表，断在 Mr. 后面，
        // 于是 Smith is here 会单独成句。
        // 修好之后 Mr. 不算句末，整段仍是同一句
        // （He said, 后面是逗号，本来就不该断开）。
        val t = "He said, \"Mr. Smith is here.\""
        assertEquals("He said, \"Mr. Smith is here.\"", sentenceOf(t, "Smith"))
    }

    @Test
    fun `无引号的称谓也不断句`() {
        assertEquals("Mr. Smith is here.", sentenceOf("Mr. Smith is here.", "Smith"))
    }

    @Test
    fun `引号内的单字母首字母不断句`() {
        // 关键：加不加引号，结论必须一样。
        // 原来不带引号时 before="B"（长度 1，判 initials，不断），
        // 带引号时 before="B（长度 2，绕过 initials，反而断）。
        assertSameSentence("The plan was B. Then we left.", "The plan was \"B. Then we left.\"")
    }

    @Test
    fun `单字母首字母后接句首词时仍按 initials 处理`() {
        val t = "The plan was B. Then we left."
        assertEquals("The plan was B. Then we left.", sentenceOf(t, "Then"))
    }

    /** 两种写法（有无引号）应当得到同样的句数 */
    private fun assertSameSentence(plain: String, quoted: String) {
        val a = sentenceOf(plain, "Then")
        val b = sentenceOf(quoted, "Then")
        // 都是整句、都没在 B. 处断开
        assertEquals(plain, a)
        assertEquals(quoted, b)
    }

    // ---- 引号内的句首词 ----

    @Test
    fun `句点后跟引号包裹的句首词应断句`() {
        // next 是 "The，next[0] 是引号 → isUpperCase false，原来不断
        val t = "It was 1990. \"The next year was hard.\""
        assertEquals("\"The next year was hard.\"", sentenceOf(t, "hard"))
    }

    @Test
    fun `缩写后跟引号包裹的句首词`() {
        val t = "He lives in the U.S. \"The economy grew.\""
        assertEquals("\"The economy grew.\"", sentenceOf(t, "economy"))
    }

    // ---- 对照组：不能改坏 ----

    @Test
    fun `普通句号断句`() {
        val t = "He left. She stayed."
        assertEquals("She stayed.", sentenceOf(t, "stayed"))
        assertEquals("He left.", sentenceOf(t, "left"))
    }

    @Test
    fun `缩写后接小写不断句`() {
        val t = "the U.S. economy grew fast."
        assertEquals("the U.S. economy grew fast.", sentenceOf(t, "economy"))
    }

    @Test
    fun `小数点不断句`() {
        val t = "It costs 3.14 dollars today."
        assertEquals("It costs 3.14 dollars today.", sentenceOf(t, "dollars"))
    }

    @Test
    fun `问号感叹号断句`() {
        val t = "Really? Yes! Fine."
        assertEquals("Really?", sentenceOf(t, "Really"))
        assertEquals("Yes!", sentenceOf(t, "Yes"))
        assertEquals("Fine.", sentenceOf(t, "Fine"))
    }
}
