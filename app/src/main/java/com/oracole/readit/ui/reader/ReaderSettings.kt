package com.oracole.readit.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.oracole.readit.ui.theme.ThemePalette

/**
 * 阅读背景。原本是枚举，为支持自定义颜色改成数据类 + 预设列表
 * 兼容：老版本存的是枚举名（"Cream" 等），读取时按 id 查预设，查不到用默认
 */
data class ReaderTheme(
    val id: String,
    val label: String,
    val bg: Color,
    val text: Color,
    val secondary: Color
) {
    val isDark: Boolean get() = bg.luminance() < 0.5f

    companion object {
        val Pure = ReaderTheme("Pure", "纯白", Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF757575))
        val Cream = ReaderTheme("Cream", "米白", Color(0xFFFAF9F5), Color(0xFF1A1A1A), Color(0xFF6F6B63))
        val Paper = ReaderTheme("Paper", "纸黄", Color(0xFFF5F0E8), Color(0xFF2B2620), Color(0xFF7A7264))
        val Sage = ReaderTheme("Sage", "豆绿", Color(0xFFE3EBDD), Color(0xFF26301F), Color(0xFF6C7A63))
        val Blue = ReaderTheme("Blue", "雾蓝", Color(0xFFE4EBF2), Color(0xFF1B2630), Color(0xFF66737F))
        val Rose = ReaderTheme("Rose", "浅粉", Color(0xFFF6E9E7), Color(0xFF2E2321), Color(0xFF7C6A67))
        val Sand = ReaderTheme("Sand", "沙色", Color(0xFFEDE4D6), Color(0xFF2B2620), Color(0xFF7A6E5F))
        val Night = ReaderTheme("Night", "深灰", Color(0xFF1F1E1B), Color(0xFFE6E2DA), Color(0xFF8F8A80))
        val Ink = ReaderTheme("Ink", "墨黑", Color(0xFF0E0E10), Color(0xFFD8D6D2), Color(0xFF807D78))

        /**
         * 跟随应用主题
         *
         * 只有一项，明暗由阅读页自己的明暗开关决定，这里不再重复设一遍
         * bg / text 是占位值，实际按应用的种子色和当前明暗现算
         * 见 ReaderSettings.resolvedTheme()
         */
        val AppFollow = ReaderTheme("app_follow", "跟随应用", Color(0xFFFAF9F5), Color(0xFF1A1A1A), Color(0xFF6F6B63))

        val presets: List<ReaderTheme> = listOf(Pure, Cream, Paper, Sage, Blue, Rose, Sand, Night, Ink)

        /** 浅色模式可选的内置配色 */
        val lightPresets: List<ReaderTheme> = listOf(Pure, Cream, Paper, Sage, Blue, Rose, Sand)

        /** 深色模式可选的内置配色 */
        val darkPresets: List<ReaderTheme> = listOf(Night, Ink)

        fun byId(id: String?): ReaderTheme? = presets.firstOrNull { it.id == id }

        /** 是不是「跟随应用主题」 */
        fun isFollowApp(id: String?): Boolean = id == AppFollow.id
    }
}

internal fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

class ReaderSettings(ctx: android.content.Context) {

    private val sp = ctx.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)

    init { migrateOnce() }

    var fontSize by mutableFloatStateOf(sp.getFloat("font_size", 18f))
        private set

    var lineHeight by mutableFloatStateOf(sp.getFloat("line_height", 1.7f))
        private set

    var margin by mutableIntStateOf(sp.getInt("margin", 20))
        private set

    /**
     * 朗读语速。1.0 是正常速度
     * 系统 TTS 官方范围 0.5–2.0，超过 2.0 多数引擎会截断到 2.0
     */
    var ttsRate by mutableFloatStateOf(sp.getFloat("tts_rate", 1.0f))
        private set

    fun updateTtsRate(v: Float) {
        ttsRate = v.coerceIn(0.5f, 2.0f)
        sp.edit().putFloat("tts_rate", ttsRate).apply()
    }

    /**
     * 浅色 / 深色是两个并列状态，各自记住自己那套背景
     *
     * 为什么 — 用户在浅色下挑了一个自定义色，切到深色又挑一个
     * 再切回浅色时不该被深色那次覆盖掉
     * 所以背景色存在两个槽里：theme_light / theme_dark
     * 自定义色也是两个槽：custom_bg_light / custom_bg_dark
     */
    var darkMode by mutableStateOf(sp.getBoolean("dark_mode", false))
        private set

    var theme by mutableStateOf(loadTheme(sp.getBoolean("dark_mode", false)))
        private set

    val isCustom: Boolean get() = theme.id == CUSTOM_ID

    /** 当前模式下可选的配色 */
    val presetsForMode: List<ReaderTheme>
        get() = if (darkMode) ReaderTheme.darkPresets else ReaderTheme.lightPresets

    fun toggleDark(dark: Boolean) {
        if (darkMode == dark) return
        darkMode = dark
        sp.edit().putBoolean("dark_mode", dark).apply()
        theme = loadTheme(dark)
    }

    /**
     * 收藏时按原形存
     *
     * 关：点 found 就存 found
     * 开：用 AI 结合上下文判定的原形，建立美国的 found 存 found
     * 找到钥匙的 found 存 find。词典的 lemma 表做不到这件事
     * 它只知道形态，不知道这句里是哪个词
     */
    var collectByBase by mutableStateOf(sp.getBoolean("collect_by_base", true))
        private set

    fun updateCollectByBase(v: Boolean) {
        collectByBase = v
        sp.edit().putBoolean("collect_by_base", v).apply()
    }

    /**
     * 详情页是否显示词形区块。默认关
     *
     * 关掉后连请求也不发。它和按原形收藏解决的是同一件事
     * 同时开两个没意义
     */
    var showWordForms by mutableStateOf(sp.getBoolean("show_word_forms", false))
        private set

    fun updateShowWordForms(v: Boolean) {
        showWordForms = v
        sp.edit().putBoolean("show_word_forms", v).apply()
    }

    var markCollected by mutableStateOf(sp.getBoolean("mark_collected", false))
        private set

    fun updateMarkCollected(v: Boolean) {
        markCollected = v
        sp.edit().putBoolean("mark_collected", v).apply()
    }

    fun updateFontSize(v: Float) {
        fontSize = v.coerceIn(14f, 26f)
        sp.edit().putFloat("font_size", fontSize).apply()
    }

    fun updateLineHeight(v: Float) {
        lineHeight = v.coerceIn(1.3f, 2.2f)
        sp.edit().putFloat("line_height", lineHeight).apply()
    }

    fun updateMargin(v: Int) {
        margin = v.coerceIn(8, 48)
        sp.edit().putInt("margin", margin).apply()
    }

    fun updateTheme(t: ReaderTheme) {
        theme = t
        sp.edit().putString(slotKey(darkMode), t.id).apply()
    }

    /**
     * 应用的种子色，由外层注入（ReadItApp 在组合时同步）
     *
     * 跟随应用主题的两项要靠它现算配色。0 表示应用跟随壁纸
     * 那时拿不到种子，退回内置紫，保证背景至少带一点主色调
     * 而不是变成一块和界面无关的灰
     */
    var appSeed by mutableIntStateOf(0)

    // resolvedTheme 会被多处每次重组调用，ThemePalette 里要做色彩空间转换
    // 所以按 (种子, 明暗) 缓存一份。不缓存的话每次重组都算一遍，转场时明显掉帧
    private var cacheKey: String? = null
    private var cacheValue: ReaderTheme? = null

    /**
     * 实际用来绘制的配色
     *
     * 普通预设直接返回自己；跟随应用这两项现算，
     * 背景取应用配色的 background，正文取 onBackground
     * 次要文字取 onSurfaceVariant。这样阅读页和应用整体是同一套色系
     * 又各自保留自己的排版设置
     */
    fun resolvedTheme(): ReaderTheme {
        val cur = theme
        if (!ReaderTheme.isFollowApp(cur.id)) return cur
        // 明暗跟阅读页自己的开关，不跟背景选项，背景只是一个配色来源
        val dark = darkMode
        val key = "${appSeed}_$dark"
        cacheValue?.let { if (cacheKey == key) return it }

        val seed = if (appSeed == 0) 0xFF6750A4.toInt() else appSeed
        // 不用 scheme.background，那个接近中性，九种主色算出来几乎一样
        // 看不出跟随的是哪个色。见 ThemePalette.readingThemeFrom 的说明
        val tone = ThemePalette.readingThemeFrom(seed, dark)
        val built = ReaderTheme(
            id = cur.id,
            label = cur.label,
            bg = Color(tone.bg),
            text = Color(tone.text),
            secondary = Color(tone.secondary)
        )
        cacheKey = key
        cacheValue = built
        return built
    }

    private fun slotKey(dark: Boolean) = if (dark) "theme_dark" else "theme_light"
    private fun customKey(dark: Boolean) = if (dark) "custom_bg_dark" else "custom_bg_light"

    /** 自定义背景色；文字色按背景亮度自动选，保证对比度 */
    fun updateCustomBg(bg: Color) {
        theme = customOf(bg)
        sp.edit()
            .putString(slotKey(darkMode), CUSTOM_ID)
            .putInt(customKey(darkMode), bg.toArgb())
            .apply()
    }

    private fun customOf(bg: Color): ReaderTheme {
        val dark = bg.luminance() < 0.5f
        return ReaderTheme(
            CUSTOM_ID, "自定义", bg,
            if (dark) Color(0xFFE6E2DA) else Color(0xFF1A1A1A),
            if (dark) Color(0xFF9A968F) else Color(0xFF75716B)
        )
    }

    /**
     * 上次保存的自定义背景色。没存过就返回 null
     *
     * 调色盘要用它作为初始值，否则切到别的主题再打开调色盘
     * 会以当前主题色为起点，看起来像"自定义色没了"
     */
    fun savedCustomBg(): Color? {
        val argb = sp.getInt(customKey(darkMode), 0)
        return if (argb != 0) Color(argb) else null
    }

    /**
     * 读某个模式下的背景
     *
     * 老数据只有 "theme" 和 "custom_bg" 两个键，[migrateOnce] 会把它们
     * 搬到对应模式的槽里，所以这里只需要读新键
     */
    private fun loadTheme(dark: Boolean): ReaderTheme {
        val fallback = if (dark) ReaderTheme.Night else ReaderTheme.Cream
        val id = sp.getString(slotKey(dark), null)
            ?: return fallback
        // 跟随应用不在 presets 里，byId 查不到会退回预设，
        // 必须先单独判断，否则选了它、切一次明暗再切回来就丢了
        if (ReaderTheme.isFollowApp(id)) return ReaderTheme.AppFollow
        if (id == CUSTOM_ID) {
            val argb = sp.getInt(customKey(dark), 0)
            if (argb != 0) return customOf(Color(argb))
        }
        return ReaderTheme.byId(id) ?: fallback
    }

    /** 老版本只有一套背景，升级时搬进对应模式，尽量保留用户原来的选择 */
    private fun migrateOnce() {
        if (sp.contains("theme_light") || sp.contains("theme_dark")) return
        val oldId = sp.getString("theme", null) ?: return
        val oldCustom = sp.getInt("custom_bg", 0)
        val old = if (oldId == CUSTOM_ID && oldCustom != 0) customOf(Color(oldCustom))
        else ReaderTheme.byId(oldId) ?: return
        val edit = sp.edit()
        if (old.isDark) {
            edit.putString("theme_dark", oldId)
            if (oldCustom != 0) edit.putInt("custom_bg_dark", oldCustom)
            edit.putBoolean("dark_mode", true)
        } else {
            edit.putString("theme_light", oldId)
            if (oldCustom != 0) edit.putInt("custom_bg_light", oldCustom)
        }
        edit.apply()
    }

    companion object {
        const val CUSTOM_ID = "custom"
    }
}
