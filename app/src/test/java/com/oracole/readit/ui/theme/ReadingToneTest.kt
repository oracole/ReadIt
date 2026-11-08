package com.oracole.readit.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 阅读页「跟随应用主题」的配色。
 *
 * 之前这里出过错：直接取 Material 的 background，九种主色算出来是
 * FDF7FF / FFF8F7 / F8FAF0 —— 差几个色阶，界面上看不出任何变化。
 * 这个测试就是防止再退回去。
 */
class ReadingToneTest {

    private val seeds = listOf(
        0xFF6750A4.toInt() to "藤萝紫",
        0xFF0061A4.toInt() to "海湾蓝",
        0xFF006A6A.toInt() to "松石青",
        0xFF386A20.toInt() to "苔痕绿",
        0xFF7D5700.toInt() to "落日黄",
        0xFF8B5000.toInt() to "陶土橙",
        0xFFB3261E.toInt() to "赤霞红",
        0xFFB04A63.toInt() to "樱花粉",
        0xFF5A5A5A.toInt() to "石板灰",
    )

    private fun lum(c: Int): Float {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun channelSpread(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b)
    }

    /** 每种主题色算出来的背景，必须能看出色相 —— 不能是一块接近纯白/纯黑的板 */
    @Test
    fun backgroundsAreTintedNotNeutral() {
        seeds.forEach { (seed, name) ->
            val light = ThemePalette.readingThemeFrom(seed, dark = false)
            val dark = ThemePalette.readingThemeFrom(seed, dark = true)
            assertTrue(
                "$name 浅色背景 $${"%06X".format(light.bg and 0xFFFFFF)} 色差过小，看不出主题色",
                channelSpread(light.bg) >= 6
            )
            assertTrue(
                "$name 深色背景 $${"%06X".format(dark.bg and 0xFFFFFF)} 色差过小，看不出主题色",
                channelSpread(dark.bg) >= 3
            )
        }
    }

    /** 九种色的背景两两之间必须不同 */
    @Test
    fun backgroundsDifferAcrossSeeds() {
        listOf(false, true).forEach { dark ->
            val all = seeds.map { ThemePalette.readingThemeFrom(it.first, dark).bg }
            val distinct = all.toSet().size
            // 赤霞红和樱花粉本来就接近，允许一对重复；其余不能撞
            assertTrue("dark=$dark 时九种主色的背景只算出 $distinct 种，区分度不够", distinct >= 8)
        }
    }

    /** 正文和背景的亮度差必须够，否则读不清 */
    @Test
    fun textContrastsWithBackground() {
        seeds.forEach { (seed, name) ->
            listOf(false, true).forEach { dark ->
                val t = ThemePalette.readingThemeFrom(seed, dark)
                val d = abs(lum(t.text) - lum(t.bg))
                assertTrue(
                    "$name dark=$dark 正文与背景亮度差只有 $d，会读不清",
                    d > 0.55f
                )
            }
        }
    }

    /** 次要文字比正文浅，但仍要看得清 */
    @Test
    fun secondaryIsBetweenTextAndBackground() {
        seeds.forEach { (seed, name) ->
            listOf(false, true).forEach { dark ->
                val t = ThemePalette.readingThemeFrom(seed, dark)
                val lt = lum(t.text)
                val ls = lum(t.secondary)
                val lb = lum(t.bg)
                val between = ls > minOf(lt, lb) && ls < maxOf(lt, lb)
                assertTrue("$name dark=$dark 次要文字不在正文与背景之间，层级会乱", between)
            }
        }
    }

    @Test
    fun printTones() {
        println("主题色   模式   背景     正文     次要")
        seeds.forEach { (seed, name) ->
            listOf(false to "浅", true to "深").forEach { (dark, dn) ->
                val t = ThemePalette.readingThemeFrom(seed, dark)
                println(
                    "%-6s %s   %06X   %06X   %06X".format(
                        name, dn, t.bg and 0xFFFFFF, t.text and 0xFFFFFF, t.secondary and 0xFFFFFF
                    )
                )
            }
        }
    }
}
