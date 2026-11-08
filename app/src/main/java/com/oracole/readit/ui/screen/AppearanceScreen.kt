package com.oracole.readit.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oracole.readit.data.TransitionStyle
import com.oracole.readit.ui.theme.AppearanceSettings
import com.oracole.readit.ui.theme.ColorDisc
import com.oracole.readit.ui.theme.ThemePalette

/**
 * 外观
 *
 * 明暗、主色、页面转场三件事都归在这一页，它们都是应用长什么样
 * 分散在设置主页的几行里，用户想调一个时得先猜另一个在哪
 *
 * 设置在主页只留一行外观，概要文字拼出当前三项，不进这页也能看清
 */

/**
 * 主色预设
 *
 * 前七个是 Material 3 官方色板里那几支主色，紫色是 MD3 的基线主色
 * 其余按色相排开。它们色度适中，转成深浅两套都不会发灰
 * 也不会出现主色和容器色糊在一起
 *
 * 最后两个是另外加的一粉一灰：粉补足暖色一侧，灰用来把界面压成中性
 * 这两种在日常使用里被选中的概率比橙色、红色高
 */
private data class SeedOption(val name: String, val seed: Int)

/** 给设置主页的概要用：种色对应的名字 */
fun seedNameOf(seed: Int): String =
    if (seed == AppearanceSettings.SEED_WALLPAPER) "壁纸"
    else SEED_OPTIONS.firstOrNull { it.seed == seed }?.name ?: "自定义"

// 顺序不是按色相排的，是按使用偏好排的
// 松石青放第一，因为应用图标的底色是 teal，默认色和它呼应
// 藤萝紫退到倒数第二，只在石板灰前面
private val SEED_OPTIONS = listOf(
    SeedOption("松石青", 0xFF006A6A.toInt()),
    SeedOption("海湾蓝", 0xFF0061A4.toInt()),
    SeedOption("苔痕绿", 0xFF386A20.toInt()),
    SeedOption("落日黄", 0xFF7D5700.toInt()),
    SeedOption("陶土橙", 0xFF8B5000.toInt()),
    SeedOption("赤霞红", 0xFFB3261E.toInt()),
    SeedOption("樱花粉", 0xFFB04A63.toInt()),
    SeedOption("藤萝紫", 0xFF6750A4.toInt()),
    SeedOption("石板灰", 0xFF5A5A5A.toInt()),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    appearance: AppearanceSettings,
    transitionStyle: TransitionStyle,
    onTransitionChange: (TransitionStyle) -> Unit,
    onBack: () -> Unit
) {
    // 色块要按当前的明暗算，否则在深色模式下看到的是一套本该用在浅色的颜色
    val dark = appearance.darkNow(androidx.compose.foundation.isSystemInDarkTheme())

    // 六个预设的色块数据。dark 变了才重算，Hct 转换有成本，不能每次重组都算
    val swatches = remember(dark) {
        SEED_OPTIONS.map { it.seed to ThemePalette.swatchOf(it.seed, dark) }
    }
    // 跟随壁纸那一项没有固定种子，用一个中性的灰蓝画示意，
    // 它的意思是颜色由系统决定，画具体主色反而不准确
    val wallpaperSwatch = remember(dark) {
        ThemePalette.swatchOf(0xFF4A6FA5.toInt(), dark)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            SectionLabel("明暗")
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                ModeRow("跟随系统", AppearanceSettings.MODE_SYSTEM, appearance) 
                ModeRow("浅色", AppearanceSettings.MODE_LIGHT, appearance)
                ModeRow("深色", AppearanceSettings.MODE_DARK, appearance)
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("主色")
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                // 色块之间留得开一些。挤在一起时每块都在争视线
                // 整排看起来像一条色带，选色反而更难
                horizontalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                // 跟随壁纸
                DiscItem(
                    label = "壁纸",
                    selected = appearance.seed == AppearanceSettings.SEED_WALLPAPER,
                    onClick = { appearance.updateSeed(AppearanceSettings.SEED_WALLPAPER) }
                ) {
                    ColorDisc(
                        swatch = wallpaperSwatch,
                        dashedRing = true,
                        checked = appearance.seed == AppearanceSettings.SEED_WALLPAPER
                    )
                }
                SEED_OPTIONS.forEachIndexed { i, opt ->
                    val sw = swatches[i].second
                    DiscItem(
                        label = opt.name,
                        selected = appearance.seed == opt.seed,
                        onClick = { appearance.updateSeed(opt.seed) }
                    ) {
                        ColorDisc(
                            swatch = sw,
                            checked = appearance.seed == opt.seed
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("页面转场")
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                TransitionStyle.entries.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onTransitionChange(s) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                s.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (transitionStyle == s) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun ModeRow(label: String, value: Int, appearance: AppearanceSettings) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { appearance.updateMode(value) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RadioButton(
            selected = appearance.mode == value,
            onClick = { appearance.updateMode(value) }
        )
    }
}

@Composable
private fun DiscItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    disc: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        disc()
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            // 三个字的名字在 74dp 里刚好一行。不锁单行的话
            // 系统字体调大时会折成两行，色块高度参差不齐
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
