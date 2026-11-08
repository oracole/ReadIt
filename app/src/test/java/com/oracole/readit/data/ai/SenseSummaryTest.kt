package com.oracole.readit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 释义压缩规则的回归。
 *
 * 用例来自实际出问题的截图：
 * lagom 返回 adj. + adv. 两个词性（正常）
 * ikigai 把同一个 n. 拆成两条返回（要合并）
 */
class SenseSummaryTest {

    private fun s(pos: String, meaning: String) = SenseLine(pos, meaning, "")

    @Test
    fun `同一词性合并为一条`() {
        val r = senseSummary(
            listOf(
                s("n.", "生起甲斐，日本概念：活着的意义、生的价值"),
                s("n.", "（引申）使人每天起床的动力")
            )
        )
        assertEquals("n. 生起甲斐，日本概念：活着的意义、生的价值；（引申）使人每天起床的动力", r)
        // 词性只出现一次
        assertEquals(1, Regex("n\\.").findAll(r).count())
    }

    @Test
    fun `不同词性各占一行`() {
        val r = senseSummary(
            listOf(
                s("adj.", "不多不少，恰到好处"),
                s("adv.", "适度地，刚刚好地")
            )
        )
        assertEquals("adj. 不多不少，恰到好处\nadv. 适度地，刚刚好地", r)
    }

    @Test
    fun `同词性分散在三处也合并`() {
        val r = senseSummary(
            listOf(s("n.", "甲"), s("v.", "乙"), s("n.", "丙"), s("v.", "丁"))
        )
        // 按首次出现顺序：n 先于 v
        assertEquals("n. 甲；丙\nv. 乙；丁", r)
    }

    @Test
    fun `空词性不加前缀`() {
        val r = senseSummary(listOf(s("", "某义"), s("n.", "名词义")))
        assertEquals("某义\nn. 名词义", r)
    }

    @Test
    fun `空白义项被丢弃`() {
        val r = senseSummary(listOf(s("n.", "  "), s("n.", "有效"), s("", "")))
        assertEquals("n. 有效", r)
    }

    @Test
    fun `空列表返回空串`() {
        assertEquals("", senseSummary(emptyList()))
    }

    @Test
    fun `词性前后空格被去掉`() {
        val r = senseSummary(listOf(s(" n. ", "甲"), s("n.", "乙")))
        assertEquals("n. 甲；乙", r)
    }

    @Test
    fun `释义内部逗号不受影响`() {
        val r = senseSummary(listOf(s("adj.", "不多不少，恰到好处")))
        assertEquals("adj. 不多不少，恰到好处", r)
    }
}
