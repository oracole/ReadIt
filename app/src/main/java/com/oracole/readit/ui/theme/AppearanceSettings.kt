package com.oracole.readit.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 应用外观：明暗模式 + 主色种子
 *
 * 和 ReaderSettings 分开存，那套管阅读页正文的排版和背景
 * 这套管应用本身（书架、设置、词汇页）。两套互不影响
 * 换应用主色不该把用户挑的阅读背景冲掉
 */
class AppearanceSettings(ctx: Context) {

    private val sp = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    /** [MODE_SYSTEM] 跟随系统 / [MODE_LIGHT] 浅色 / [MODE_DARK] 深色 */
    var mode by mutableIntStateOf(sp.getInt(KEY_MODE, MODE_SYSTEM))
        private set

    fun updateMode(v: Int) {
        mode = v.coerceIn(MODE_SYSTEM, MODE_DARK)
        sp.edit().putInt(KEY_MODE, mode).apply()
    }

    /**
     * 主色的种子色，ARGB
     *
     * 0 表示跟随壁纸（dynamicColorScheme）
     * 非 0 时要按该色生成整套配色，那一步依赖
     * material-color-utilities，等依赖到位再接
     */
    var seed by mutableIntStateOf(sp.getInt(KEY_SEED, SEED_WALLPAPER))
        private set

    fun updateSeed(argb: Int) {
        seed = argb
        sp.edit().putInt(KEY_SEED, argb).apply()
    }

    /**
     * 界面字体。存的是 key 字符串，不是序号，
     *
     * 导入的字体存在 files 目录里，序号会随导入列表变化而错位
     * 存序号的话删掉一款字体、后面全串位
     *
     * 和阅读页正文字体分开，那个在 ReaderSettings 里
     */
    var fontKey by mutableStateOf(sp.getString(KEY_FONT, "system") ?: "system")
        private set

    fun updateFont(v: UiFont) {
        fontKey = UiFont.keyOf(v)
        sp.edit().putString(KEY_FONT, fontKey).apply()
    }

    /**
     * 导入字体的清单缓存
     *
     * 主题层每帧重组都要知道当前字体，不能每次都去读磁盘
     * 导入或删除后调 refreshFonts 刷一次
     */
    var customFonts by mutableStateOf<List<UiFont.Custom>>(emptyList())
        private set

    fun refreshFonts(list: List<UiFont.Custom>) {
        customFonts = list
    }

    /** 拿到实际字体 */
    val uiFont: UiFont get() = UiFont.fromKey(fontKey, customFonts)

    /**
     * 阅读页正文字体
     *
     * 和界面字体共用一个列表，但状态分开存，
     * 界面用黑体、正文用宋体是常见组合，绑成一个的话改一个另一个跟着变
     *
     * 默认宋体，也就是这个功能没做之前阅读页的样子
     */
    var readingFontKey by mutableStateOf(sp.getString(KEY_READING_FONT, "serif") ?: "serif")
        private set

    fun updateReadingFont(v: UiFont) {
        readingFontKey = UiFont.keyOf(v)
        sp.edit().putString(KEY_READING_FONT, readingFontKey).apply()
    }

    val readingFont: UiFont get() = UiFont.fromKey(readingFontKey, customFonts)

    /** 指定模式下的实际明暗。跟随系统时读系统设置 */
    fun darkNow(systemDark: Boolean): Boolean = when (mode) {
        MODE_LIGHT -> false
        MODE_DARK -> true
        else -> systemDark
    }

    companion object {
        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        const val SEED_WALLPAPER = 0

        private const val KEY_MODE = "mode"
        private const val KEY_SEED = "seed"
        /**
         * 换过 key
         *
         * 旧版这里存的是 Int（enum 序号），新版改成存字符串
         * 同一个 key 用 getString 去读一个 Int，会抛 ClassCastException
         * 而且是在本类构造时抛，也就是应用启动就崩
         *
         * 换个新 key，旧值留着不读，不用做迁移
         */
        private const val KEY_FONT = "ui_font_v2"

        /** 新 key，和界面字体分开存 */
        private const val KEY_READING_FONT = "reading_font"
    }
}
