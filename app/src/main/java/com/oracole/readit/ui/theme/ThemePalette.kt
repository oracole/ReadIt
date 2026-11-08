package com.oracole.readit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.SchemeNeutral
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.google.android.material.color.utilities.TonalPalette

/**
 * 从一个种子色生成整套 Material 配色
 *
 * 用的是 material-color-utilities（Android 12 系统动态取色的同一套算法）
 * 好处是不用自己调主色多亮、容器色多暗这类参数，
 * 换任何颜色都不会出现对比度不够或配色发脏的问题
 *
 * 调用开销：Hct.fromInt 要做 sRGB 与 CAM16 之间的色彩空间转换
 * 单次一两毫秒，但绝不能放在每次重组都执行的位置，调用方必须 remember
 */
object ThemePalette {

    /**
     * @param seed 种子色的 ARGB
     * @param dark 生成深色还是浅色配色
     */
    fun schemeFrom(seed: Int, dark: Boolean): ColorScheme =
        build(scheme(seed, dark), dark)

    // MaterialDynamicColors 是普通类（MaterialDynamicColors() 构造）
    // 不是单例对象。而且它的 DynamicColor 实例是成员方法、每次调用返回新对象
    // 所以提到外面复用没有意义，只有本类的实例值得缓存
    private val mdc = MaterialDynamicColors()

    /**
     * 用 SchemeTonalSpot，不用 SchemeContent
     *
     * 这不是偏好问题，SchemeContent 在 material-color-utilities 1.13 里
     * 取 primaryContainer / tertiaryContainer 会退化成种子色本身（深浅两套
     * 给出一模一样的结果），圆盘上左块和右下块就成了两块纯种子色
     *
     * TonalSpot 是 Android 12 系统取色实际用的变体（DefaultVariant = TONAL_SPOT）
     * 各角色取值正常，也更贴近系统观感
     */
    private fun scheme(seed: Int, dark: Boolean): DynamicScheme {
        val hct = Hct.fromInt(seed)
        // 色度过低（纯灰、接近黑的颜色）时改用中性方案
        //
        // TonalSpot 会把色度往高拉，纯灰 #5A5A5A 生成出来的主色是青蓝色
        // （实测 FF82D3E0），和用户选的灰完全不搭
        // Neutral 保留了低色度，出来是带一点色调的中性灰
        return if (hct.chroma < 10.0) SchemeNeutral(hct, dark, 0.0)
        else SchemeTonalSpot(hct, dark, 0.0)
    }

    private fun build(s: DynamicScheme, dark: Boolean): ColorScheme {
        val m = mdc

        fun c(d: DynamicColor): Color = Color(s.getArgb(d))

        return if (dark) darkColorScheme(
            primary = c(m.primary()),
            onPrimary = c(m.onPrimary()),
            primaryContainer = c(m.primaryContainer()),
            onPrimaryContainer = c(m.onPrimaryContainer()),
            inversePrimary = c(m.inversePrimary()),
            secondary = c(m.secondary()),
            onSecondary = c(m.onSecondary()),
            secondaryContainer = c(m.secondaryContainer()),
            onSecondaryContainer = c(m.onSecondaryContainer()),
            tertiary = c(m.tertiary()),
            onTertiary = c(m.onTertiary()),
            tertiaryContainer = c(m.tertiaryContainer()),
            onTertiaryContainer = c(m.onTertiaryContainer()),
            background = c(m.background()),
            onBackground = c(m.onBackground()),
            surface = c(m.surface()),
            onSurface = c(m.onSurface()),
            surfaceVariant = c(m.surfaceVariant()),
            onSurfaceVariant = c(m.onSurfaceVariant()),
            surfaceTint = c(m.surfaceTint()),
            inverseSurface = c(m.inverseSurface()),
            inverseOnSurface = c(m.inverseOnSurface()),
            error = c(m.error()),
            onError = c(m.onError()),
            errorContainer = c(m.errorContainer()),
            onErrorContainer = c(m.onErrorContainer()),
            outline = c(m.outline()),
            outlineVariant = c(m.outlineVariant()),
            scrim = c(m.scrim()),
            surfaceBright = c(m.surfaceBright()),
            surfaceDim = c(m.surfaceDim()),
            surfaceContainer = c(m.surfaceContainer()),
            surfaceContainerHigh = c(m.surfaceContainerHigh()),
            surfaceContainerHighest = c(m.surfaceContainerHighest()),
            surfaceContainerLow = c(m.surfaceContainerLow()),
            surfaceContainerLowest = c(m.surfaceContainerLowest()),
        )
        else lightColorScheme(
            primary = c(m.primary()),
            onPrimary = c(m.onPrimary()),
            primaryContainer = c(m.primaryContainer()),
            onPrimaryContainer = c(m.onPrimaryContainer()),
            inversePrimary = c(m.inversePrimary()),
            secondary = c(m.secondary()),
            onSecondary = c(m.onSecondary()),
            secondaryContainer = c(m.secondaryContainer()),
            onSecondaryContainer = c(m.onSecondaryContainer()),
            tertiary = c(m.tertiary()),
            onTertiary = c(m.onTertiary()),
            tertiaryContainer = c(m.tertiaryContainer()),
            onTertiaryContainer = c(m.onTertiaryContainer()),
            background = c(m.background()),
            onBackground = c(m.onBackground()),
            surface = c(m.surface()),
            onSurface = c(m.onSurface()),
            surfaceVariant = c(m.surfaceVariant()),
            onSurfaceVariant = c(m.onSurfaceVariant()),
            surfaceTint = c(m.surfaceTint()),
            inverseSurface = c(m.inverseSurface()),
            inverseOnSurface = c(m.inverseOnSurface()),
            error = c(m.error()),
            onError = c(m.onError()),
            errorContainer = c(m.errorContainer()),
            onErrorContainer = c(m.onErrorContainer()),
            outline = c(m.outline()),
            outlineVariant = c(m.outlineVariant()),
            scrim = c(m.scrim()),
            surfaceBright = c(m.surfaceBright()),
            surfaceDim = c(m.surfaceDim()),
            surfaceContainer = c(m.surfaceContainer()),
            surfaceContainerHigh = c(m.surfaceContainerHigh()),
            surfaceContainerHighest = c(m.surfaceContainerHighest()),
            surfaceContainerLow = c(m.surfaceContainerLow()),
            surfaceContainerLowest = c(m.surfaceContainerLowest()),
        )
    }

    /**
     * 圆形色块要显示的四块颜色
     *
     * 和设计稿一致：中心圆点、左半圆、右上四分之一、右下四分之一
     *
     * 用 primaryContainer / secondaryContainer / tertiaryContainer 三族
     * 而不是 SurfaceContainer 系列，后者三个都接近中性灰，圆盘会糊成一片
     * 分不出颜色。这三族同源但有色相差，拼出来才像设计稿那样四块分明
     */
    data class Swatch(
        val center: Int,
        val left: Int,
        val topRight: Int,
        val bottomRight: Int
    )

    fun swatchOf(seed: Int, dark: Boolean): Swatch {
        val s = scheme(seed, dark)
        // 三块外圈用三个调色板、三个不同音调，而不是三个角色的 container
        //
        // 原因：小色度的种子（中性灰、很淡的粉）下 primary 和 secondary 调色板
        // 几乎重合，container 取出来是同一个颜色，圆盘上两块会糊成一整片
        // 音调不同则亮度必然不同，四块永远分得开
        val p = s.primaryPalette
        val sec = s.secondaryPalette
        val ter = s.tertiaryPalette
        return if (dark) Swatch(
            center = s.getArgb(mdc.primary()),
            left = p.tone(30),
            topRight = sec.tone(40),
            bottomRight = ter.tone(25)
        ) else Swatch(
            center = s.getArgb(mdc.primary()),
            left = p.tone(90),
            topRight = sec.tone(80),
            bottomRight = ter.tone(70)
        )
    }

    /**
     * 阅读页的跟随应用主题配色
     *
     * 不能直接用 scheme.background，Material 把 background 定得接近中性
     * 九种主色算出来分别是 FDF7FF / FFF8F7 / F8FAF0，差几个色阶，肉眼看不出来
     * 那是给整屏打底用的，带明显色相会显得脏
     *
     * 所以从主色调色板取，但把色度压低到 9：
     * 保留色相看得出是什么主题色，又不至于像 tone95 那样刺眼
     * （松石青 t95 是 D2FFFE，当阅读背景没法看）
     *
     * 正文用同色相、色度 2 的中性调色板，保证和背景有足够亮度差
     */
    fun readingThemeFrom(seed: Int, dark: Boolean): ReadingTone {
        val hct = Hct.fromInt(seed)
        val hue = hct.hue
        // 低饱和的主色（石板灰）不能再压，否则连色相都没了
        val bgChroma = if (hct.chroma < 10.0) 6.0 else 9.0
        val bg = TonalPalette.fromHueAndChroma(hue, bgChroma)
        val ink = TonalPalette.fromHueAndChroma(hue, 2.0)
        return if (dark) ReadingTone(
            bg = bg.tone(18),
            text = ink.tone(92),
            secondary = ink.tone(68)
        ) else ReadingTone(
            bg = bg.tone(97),
            text = ink.tone(12),
            secondary = ink.tone(45)
        )
    }

    /** 阅读页用的一组颜色，三个都是 ARGB */
    data class ReadingTone(val bg: Int, val text: Int, val secondary: Int)
}
