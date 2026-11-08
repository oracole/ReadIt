package com.oracole.readit.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 语境义缓存键。
 *
 * 这里的每个用例都还原同一个现场：一个段落里同一个词出现两次，
 * 两处的「上句 + 本句 + 下句」拼出完全相同的字符串。
 *
 * 1.7.0 之前键是「词 + 上下文 + 页签」，于是第二处会拿到第一处的答案。
 * 现在键里多了「所在句」，两处必须不同。
 */
class SenseKeyTest {

    /** 在 text 里找第 n 次出现的 word，返回 (所在句, 上下文) */
    private fun locate(text: String, word: String, nth: Int): Pair<String, String> {
        var from = 0
        repeat(nth) {
            val i = text.indexOf(word, from, ignoreCase = true)
            require(i >= 0) { "第 $nth 次找不到 $word" }
            from = i + word.length
        }
        val start = text.indexOf(word, from, ignoreCase = true)
        require(start >= 0) { "找不到 $word" }
        return Tokenizer.sentenceAndContext(text, start, start + word.length)
    }

    /** 断言：两处的句子不同、上下文相同、键不同 */
    private fun assertDistinct(text: String, word: String) {
        val (s0, c0) = locate(text, word, 0)
        val (s1, c1) = locate(text, word, 1)

        // 前提：这个段落确实是「上下文一样」的那种，否则测不到东西
        assertEquals("用例本身不成立：两处上下文应相同", c0, c1)
        assertNotEquals("用例本身不成立：两处所在句应不同", s0, s1)

        // 旧键：词 + 上下文 + 页签 —— 必须会撞，证明这正是当初的 bug
        val old0 = "${word.lowercase()}|${c0.hashCode()}|SENSE"
        val old1 = "${word.lowercase()}|${c1.hashCode()}|SENSE"
        assertEquals("旧键应当相撞，否则用例没有还原到现场", old0, old1)

        // 新键：多带所在句 —— 必须不同
        val new0 = senseKey(word, s0, c0, "SENSE")
        val new1 = senseKey(word, s1, c1, "SENSE")
        assertNotEquals("同段两处的键仍然相同，修复无效", new0, new1)
    }

    @Test
    fun observed() {
        assertDistinct(
            "The study reports on observed changes in the climate. " +
                "Scientists observed climate change and came to this conclusion.",
            "observed"
        )
    }

    @Test
    fun record() {
        assertDistinct(
            "She kept a careful record of every payment. " +
                "Auditors record each transaction for later review.",
            "record"
        )
    }

    @Test
    fun objectNounVsVerb() {
        assertDistinct(
            "The object on the table was a small brass box. " +
                "Most residents object to the new parking rules.",
            "object"
        )
    }

    @Test
    fun minute() {
        assertDistinct(
            "Wait a minute before you answer the question. " +
                "The difference between the two samples is minute and easy to miss.",
            "minute"
        )
    }

    @Test
    fun present() {
        assertDistinct(
            "She received a present from her brother on her birthday. " +
                "All members were present at the meeting.",
            "present"
        )
    }

    @Test
    fun close() {
        assertDistinct(
            "Please close the window before you leave the room. " +
                "They have been close friends since childhood.",
            "close"
        )
    }

    /** 页签不同 → 键不同（原本就该如此，防止以后改键时漏掉） */
    @Test
    fun differentTabsNeverCollide() {
        val (s, c) = locate("Please close the window before you leave the room.", "close", 0)
        assertNotNull(s)
        assertNotEquals(senseKey("close", s, c, "SENSE"), senseKey("close", s, c, "ROOT"))
    }

    /**
     * 跨段：上下文本来就不同，两处键不同。
     * 这是回归验证 —— 修复不能把原本正常的情况改坏。
     */
    @Test
    fun crossParagraphStillWorks() {
        val para = "The bank refused to lend the money without collateral. " +
            "She walked along the bank of the river at dusk."
        val (s0, c0) = locate(para, "bank", 0)
        val (s1, c1) = locate(para, "bank", 1)
        assertNotEquals(senseKey("bank", s0, c0, "SENSE"), senseKey("bank", s1, c1, "SENSE"))
    }

    /**
     * 同一句内出现两次：键相同。
     * 这是设计上的已知限制，不是缺陷 —— 同一句里 AI 给的语境义本就该是同一个。
     */
    @Test
    fun sameSentenceIsKnownLimitation() {
        val text = "The record is a record of failure."
        val (s0, c0) = locate(text, "record", 0)
        val (s1, c1) = locate(text, "record", 1)
        assertEquals("同一句内的键本就相同，这是已知限制", s0, s1)
        assertEquals(senseKey("record", s0, c0, "SENSE"), senseKey("record", s1, c1, "SENSE"))
    }
}
