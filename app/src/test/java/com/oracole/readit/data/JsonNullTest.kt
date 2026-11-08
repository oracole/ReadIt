package com.oracole.readit.data

import com.oracole.readit.data.ai.parseSense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON null 清洗。
 *
 * 背景：Android 自带的 org.json 里，optString(key, fallback) 遇到显式 null
 * 返回字面量 "null"，不走 fallback。模型把空字段写成 `"base": null` 时，
 * 收藏记录会变成一个叫 null 的词。
 *
 * 这里测的是所有解析入口都过了 scrubNulls —— 包括 parseSense，
 * 它曾经自己 new JSONObject，绕过了清洗。
 */
class JsonNullTest {

    @Test
    fun `sense 的 base 为 null 时变空串`() {
        val r = parseSense(
            """{"base":null,"sense_here":"甲","common":[],"pos":"n.","note":"","dict_gloss":"","confidence":"high"}"""
        )!!
        assertEquals("", r.base)
        assertEquals("甲", r.senseHere)
    }

    @Test
    fun `sense 的 dict_gloss 为 null 时变空串`() {
        val r = parseSense(
            """{"base":"run","sense_here":"甲","common":[],"pos":"n.","dict_gloss":null,"confidence":"high"}"""
        )!!
        assertEquals("", r.dictGloss)
    }

    @Test
    fun `sense 的 pos 为 null 时被丢掉`() {
        val r = parseSense(
            """{"base":"run","sense_here":"甲","common":[],"pos":null,"confidence":"high"}"""
        )!!
        assertEquals("", r.pos)
    }

    @Test
    fun `common 数组里的 null 元素被滤掉`() {
        val r = parseSense(
            """{"base":"run","sense_here":"甲","common":[null,"常用"],"pos":"n.","confidence":"high"}"""
        )!!
        assertEquals(listOf("常用"), r.common)
    }

    @Test
    fun `旧行为对照 无 null 时不受影响`() {
        val r = parseSense(
            """{"base":"run","sense_here":"跑","common":["跑步","经营"],"pos":"v.","note":"口语","dict_gloss":"v. 跑","confidence":"high"}"""
        )!!
        assertEquals("run", r.base)
        assertEquals(listOf("跑步", "经营"), r.common)
        assertEquals("v.", r.pos)
        assertEquals("口语", r.note)
        assertEquals("v. 跑", r.dictGloss)
    }

    @Test
    fun `词组的 base 同样被清洗`() {
        val r = com.oracole.readit.data.ai.parseSense(
            """{"base":null,"sense_here":"整体义","common":[],"pos":"phr.","confidence":"low"}"""
        )!!
        assertEquals("", r.base)
        assertTrue(r.low)
    }
}
