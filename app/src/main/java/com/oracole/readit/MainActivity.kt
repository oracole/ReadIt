package com.oracole.readit

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import com.oracole.readit.ui.ReadItApp
import com.oracole.readit.ui.theme.AppearanceSettings
import com.oracole.readit.ui.theme.ReadItTheme
import com.oracole.readit.util.AiLog
import com.oracole.readit.util.Tts

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 三参数重载：lightScrim, darkScrim, detectDarkMode
        // 两个参数那版无法自定义 scrim，会套用系统默认的半透明黑
        // 把状态栏区域压暗，看起来就像 AppBar 没有铺到屏幕顶端
        val detectLight: (android.content.res.Resources) -> Boolean = { false }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, detectLight),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, detectLight)
        )
        super.onCreate(savedInstanceState)

        // 预热语音引擎：构造是异步的，等到点击才 new 第一次必然没声音
        Tts.init(this)
        AiLog.init(this)
        setContent {
            // 同一份 AppearanceSettings 既给主题用、也给设置页用
            // 各建一份的话，设置页改了 mode，主题那边不会重组
            val appearance = remember { AppearanceSettings(this) }
            val dark = appearance.darkNow(isSystemInDarkTheme())
            ReadItTheme(darkTheme = dark, seed = appearance.seed, font = appearance.uiFont) {
                ReadItApp(appearance = appearance)
            }
        }
    }
}
