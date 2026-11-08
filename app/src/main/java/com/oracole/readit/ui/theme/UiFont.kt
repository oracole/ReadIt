package com.oracole.readit.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * 界面字体
 *
 * 只影响应用界面，阅读页正文由 ReaderSettings 那套管，两者分开
 *
 * 三档，系统默认、内置 JP、用户导入
 *
 * 曾经试过把 ttc 合集打包、按 ttcIndex 取 face，那条路在 Android 上取不到
 * 指定 face，五个字形出来一模一样。内置的 JP 现在是单独的 otf
 * 一个文件一个字形，不涉及 index
 */
sealed interface UiFont {

    val label: String
    val desc: String

    /** 不打包字体，用系统默认 */
    data object System : UiFont {
        override val label = "跟随系统"
        override val desc = "使用设备当前字体"
    }

    // 内置的等宽字体，Fira Code 可变字重
    // 只有拉丁字母和符号，没有汉字，中文会自动退回系统字体
    // 用在中文界面里，效果是英文数字走等宽、汉字还是系统黑体
    data object FiraCode : UiFont {
        override val label = "Fira Code"
        override val desc = "等宽，仅西文和符号，中文仍用系统字体"
    }

    /** 衬线，阅读页原本的样式 */
    data object Serif : UiFont {
        override val label = "宋体"
        override val desc = "衬线，阅读页当前使用的样式"
    }

    /** 用户自己导入的字体 */
    data class Custom(val fileName: String, val displayName: String) : UiFont {
        override val label: String get() = displayName
        override val desc: String get() = "已导入"
    }

    companion object {

        // 内置的三项，导入的另外拼在后面
        val builtIn: List<UiFont> = listOf(System, FiraCode, Serif)

        /** 存偏好用。内置存固定串，导入的存文件名 */
        fun keyOf(f: UiFont): String = when (f) {
            is System -> "system"
            is FiraCode -> "fira"
            is Serif -> "serif"
            is Custom -> "custom:" + f.fileName
        }

        /** 从存的串还原，找不到对应的导入字体就退回系统 */
        fun fromKey(key: String?, customs: List<Custom>): UiFont = when {
            key == null -> System
            key == "fira" -> FiraCode
            key == "serif" -> Serif
            key.startsWith("custom:") -> {
                val fn = key.removePrefix("custom:")
                customs.firstOrNull { it.fileName == fn } ?: System
            }
            else -> System
        }
    }
}

/**
 * 字体加载
 *
 * Typeface 构造有成本，同一份反复构造也不会释放，所以按字体缓存
 *
 * 内置的系统和衬线不需要加载文件，Fira Code 在内置 assets 里
 * 导入的字体在 files 目录下，进不了 res，只能 Typeface.createFromFile
 */
object UiFonts {

    private val cache = HashMap<UiFont, FontFamily>()

    fun familyOf(ctx: android.content.Context, font: UiFont): FontFamily? {
        if (font is UiFont.System) return null
        cache[font]?.let { return it }

        val tf = when (font) {
            is UiFont.Custom -> {
                val f = java.io.File(ctx.filesDir, "fonts/" + font.fileName)
                if (!f.exists()) null
                else runCatching { Typeface.createFromFile(f) }.getOrNull()
            }

            // 衬线直接用系统自带的，不打包字体
            // 阅读页原来写死 FontFamily.Serif，就是这一个
            is UiFont.Serif -> return FontFamily.Serif

            // 内置的等宽字体，放 assets 不走 res
            // res 下的字体进资源表，系统启动时会扫
            is UiFont.FiraCode -> {
                val f = ensureAssetFont(ctx, "FiraCode-VF.ttf")
                if (f == null) null
                else runCatching { Typeface.createFromFile(f) }.getOrNull()
            }

            is UiFont.System -> null
        } ?: return null

        val built = FontFamily(tf)
        cache[font] = built
        return built
    }

    /** 导入或删除字体后调用，让下次加载重新读文件 */
    fun invalidate() = cache.clear()

    // 把 assets 里的字体复制到 filesDir，返回那个文件
    // Typeface.createFromFile 要真实路径，assets 里的读不到
    // 已经复制过就直接用，上一次复制到一半进程被杀的话会重来
    private fun ensureAssetFont(ctx: android.content.Context, name: String): java.io.File? =
        runCatching {
            val target = java.io.File(ctx.filesDir, name)
            if (target.exists() && target.length() > 1024) return target

            val tmp = java.io.File(ctx.filesDir, name + ".tmp")
            ctx.assets.open("fonts/$name").use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            target
        }.getOrNull()

}

/**
 * 把整套 Typography 换成指定字体
 *
 * MaterialTheme 的 Typography 有十几个样式，逐个 copy 太啰嗦
 * 而且新增样式容易漏。这里只覆盖实际用得到的那些
 * 漏掉的会退回系统默认字体，不会崩
 */
fun Typography.withFont(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family)
)
