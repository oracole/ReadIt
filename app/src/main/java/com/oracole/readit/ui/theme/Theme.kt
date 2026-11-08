package com.oracole.readit.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 兜底色板：与图标同源的紫
private val FallbackPrimary = Color(0xFF6750A4)
private val FallbackPrimaryDark = Color(0xFFD0BCFF)

private val LightScheme = lightColorScheme(
    primary = FallbackPrimary,
    onPrimary = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = FallbackPrimaryDark,
    onPrimary = Color(0xFF381E72),
)

@Composable
fun ReadItTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 主色种子。0 表示跟随壁纸 */
    seed: Int = 0,
    /** 界面字体。SYSTEM 时用系统默认，不加载内置字体 */
    font: UiFont = UiFont.System,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val scheme = when {
        // 用户指定了主色 → 按种子算
        // 这条路径在所有 Android 版本上表现一致，不再出现12 以下没有动态取色的割裂
        seed != 0 -> remember(seed, darkTheme) { ThemePalette.schemeFrom(seed, darkTheme) }

        // 跟随壁纸。只有 Android 12+ 有取色能力，以下版本退回内置紫
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)

        darkTheme -> DarkScheme
        else -> LightScheme
    }
    // 内置字体只在用户选了非 SYSTEM 时才加载
    // 加载要读 19MB 的 asset，默认那条路不该付这个代价
    val family = remember(font) {
        if (font is UiFont.System) null else UiFonts.familyOf(ctx, font)
    }
    val typography = remember(family) {
        if (family == null) Typography() else Typography().withFont(family)
    }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
