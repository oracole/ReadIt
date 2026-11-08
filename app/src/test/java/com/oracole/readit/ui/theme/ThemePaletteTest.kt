package com.oracole.readit.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 主题配色生成。
 *
 * 重点是两件事：
 *
 * 一、四块色值必须互不相同 —— 相同的话圆盘会糊成一片，看不出是什么色调。
 * 二、深色和浅色必须真的不一样，且对比度方向正确
 *     （深色模式的主色要比浅色模式亮，否则在深底上读不清）。
 */
class ThemePaletteTest {

    /** 和 AppearanceScreen 里的 SEED_OPTIONS 保持一致 */
    private val seeds = listOf(
        0xFF6750A4.toInt(), // 紫
        0xFF0061A4.toInt(), // 蓝
        0xFF006A6A.toInt(), // 青
        0xFF386A20.toInt(), // 绿
        0xFF7D5700.toInt(), // 黄
        0xFF8B5000.toInt(), // 橙
        0xFFB3261E.toInt(), // 红
        0xFFB04A63.toInt(), // 粉
        0xFF5A5A5A.toInt(), // 灰
    )

    private fun lum(c: Int): Float {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    @Test
    fun swatchFourPartsAreDistinct() {
        seeds.forEach { seed ->
            val s = ThemePalette.swatchOf(seed, dark = false)
            val all = listOf(s.center, s.left, s.topRight, s.bottomRight)
            assertEquals(
                "种子 ${Integer.toHexString(seed)} 的四块颜色有重复，圆盘会分不清",
                4,
                all.toSet().size
            )
        }
    }

    @Test
    fun swatchDiffersBetweenDarkAndLight() {
        seeds.forEach { seed ->
            val d = ThemePalette.swatchOf(seed, dark = true)
            val l = ThemePalette.swatchOf(seed, dark = false)
            assertNotEquals("深色和浅色不该给出同一套色块", d, l)
        }
    }

    @Test
    fun darkPrimaryIsBrighterThanLightPrimary() {
        seeds.forEach { seed ->
            val d = ThemePalette.schemeFrom(seed, dark = true)
            val l = ThemePalette.schemeFrom(seed, dark = false)
            val ld = lum(d.primary.value.let { (it shr 32).toInt() })
            val ll = lum(l.primary.value.let { (it shr 32).toInt() })
            assertTrue(
                "种子 ${Integer.toHexString(seed)}：深色主色应比浅色主色亮（${ld} vs ${ll}）",
                ld > ll
            )
        }
    }

    @Test
    fun surfaceContrastsWithOnSurface() {
        seeds.forEach { seed ->
            listOf(true, false).forEach { dark ->
                val c = ThemePalette.schemeFrom(seed, dark)
                val bg = lum(c.surface.value.let { (it shr 32).toInt() })
                val fg = lum(c.onSurface.value.let { (it shr 32).toInt() })
                assertTrue(
                    "种子 ${Integer.toHexString(seed)} dark=$dark："
                        + "正文色与背景色亮度差过小（$fg vs $bg），会看不清",
                    abs(fg - bg) > 0.35f
                )
            }
        }
    }

    /** 打印一份便于肉眼核对的表，跑测试时用 --info 可看到 */
    @Test
    fun printSwatches() {
        println("种子        模式  中心      左        右上      右下")
        seeds.forEach { seed ->
            listOf(true to "深", false to "浅").forEach { (dark, name) ->
                val s = ThemePalette.swatchOf(seed, dark)
                println(
                    "%08X  %s   %08X  %08X  %08X  %08X".format(
                        seed, name, s.center, s.left, s.topRight, s.bottomRight
                    )
                )
            }
        }
    }
}
