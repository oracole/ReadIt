package com.oracole.readit.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.oracole.readit.ui.reader.ReaderSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    readerSettings: ReaderSettings,
    apiConfigured: Boolean,
    /** 当前服务名，显示在「已配置 · xxx」里 */
    serviceLabel: String,
    dictInfo: String,
    articleCount: Int,
    vocabCount: Int,
    onOpenAi: () -> Unit,
    onOpenDict: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenVocabImport: () -> Unit,
    usageSummary: com.oracole.readit.data.ai.UsageSummary,
    transitionStyle: com.oracole.readit.data.TransitionStyle,
    onTransitionChange: (com.oracole.readit.data.TransitionStyle) -> Unit,
    appearanceMode: Int,
    seedLabel: String,
    onOpenAppearance: () -> Unit,
    onOpenAiLog: () -> Unit,
    fontLabel: String,
    onOpenFont: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = { MediumTopAppBar(title = { Text("设置") }, scrollBehavior = scrollBehavior) }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { GroupTitle("阅读") }
            // 背景和字号在同一个页面，合并成一项，避免两个入口指向同一处
            item {
                RowItem(
                    "阅读排版",
                    buildString {
                        append(readerSettings.theme.label)
                        append(" · ").append(readerSettings.fontSize.toInt()).append(" sp")
                        append(" · 行距 ").append(String.format("%.1f", readerSettings.lineHeight))
                    },
                    onClick = onOpenReading
                )
            }

            item { GroupTitle("朗读") }
            // 语速是全局的（词卡、详情页、整句卡片都用）
            // 放在阅读排版里不合适，那是视觉设置
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("语速", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(readerSettings.ttsRate * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = readerSettings.ttsRate,
                        // 0.5–2.0 是系统 TTS 的官方范围，超过 2.0 多数引擎会截断
                        // 步长 0.05，拖出来不会停在 1.37 这种数
                        onValueChange = {
                            readerSettings.updateTtsRate((it * 20f).roundToInt() / 20f)
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 29
                    )
                }
            }

            item { GroupTitle("词形") }
            // 两个开关解决的是同一件事，放在一起让人自己判断用哪个
            // 都开也能用，只是按原形收藏已经保证词表里就是原形了
            // 详情页再列一遍词形就是重复
            item {
                SwitchRow(
                    title = "按原形收藏",
                    desc = "在文章里收藏时，收藏选中单词的原形，而非文章中的形态",
                    checked = readerSettings.collectByBase,
                    onChange = { readerSettings.updateCollectByBase(it) }
                )
            }
            item {
                SwitchRow(
                    title = "详情页显示词形",
                    desc = "在详情页列出这个词的词性及各词性对应的原形",
                    checked = readerSettings.showWordForms,
                    onChange = { readerSettings.updateShowWordForms(it) }
                )
            }

            item { GroupTitle("界面") }
            item {
                // 明暗、主色、转场都是应用长什么样，合成一个入口
                // 概要直接拼出三项当前值，不进这一页也能看清
                RowItem(
                    "外观",
                    buildString {
                        append(
                            when (appearanceMode) {
                                com.oracole.readit.ui.theme.AppearanceSettings.MODE_LIGHT -> "浅色"
                                com.oracole.readit.ui.theme.AppearanceSettings.MODE_DARK -> "深色"
                                else -> "跟随系统"
                            }
                        )
                        append(" · ")
                        append(seedLabel)
                        append(" · ")
                        append(transitionStyle.label)
                    },
                    onClick = onOpenAppearance
                )
            }
            item {
                RowItem("字体", fontLabel, onClick = onOpenFont)
            }

            item { GroupTitle("词典") }
            item { RowItem("ECDICT 英汉词典", dictInfo, onClick = onOpenDict) }

            item { GroupTitle("AI 服务") }
            item {
                RowItem(
                    "API 接口配置",
                    if (apiConfigured) "已配置 · " + serviceLabel else "未配置",
                    highlight = apiConfigured,
                    onClick = onOpenAi
                )
            }
            item {
                RowItem(
                    "本月用量",
                    // 只写次数和 token，不放金额
                    // 换了服务以后按 DeepSeek 单价估出来的钱不是真实花费
                    // 放在概要和实际账单对不上
                    if (usageSummary.count == 0) "暂无记录"
                    else buildString {
                        append(usageSummary.count).append(" 次 · ")
                        append(String.format(java.util.Locale.US, "%,d", usageSummary.tokens))
                        append(" tokens")
                    },
                    onClick = onOpenUsage
                )
            }

            item { GroupTitle("数据") }
            item { RowItem("文章", "$articleCount 篇") }
            item { RowItem("收藏单词", "$vocabCount 个") }
            item { RowItem("导入词汇表", "CSV、TSV、Anki 导出的 txt", onClick = onOpenVocabImport) }

            item { GroupTitle("排查") }
            item {
                // AI 请求日志。之前只能靠读文件，出问题时要连电脑
                // 现在点进去能看、能复制，直接发给开发者
                RowItem(
                    "AI 请求日志",
                    "出问题时看这里",
                    onClick = onOpenAiLog
                )
            }

            item { GroupTitle("关于") }
            // 外面只露版本号，点进去才是完整的关于页
            item {
                RowItem(
                    "关于 ReadIt",
                    com.oracole.readit.BuildConfig.VERSION_NAME,
                    onClick = onOpenAbout
                )
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * 带说明文字的开关行
 *
 * 整行可点，点哪都能切换，只让 Switch 本身响应太小
 * 关闭状态说明也一直显示，不用点开才知道这一项干什么
 */
@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RowItem(
    title: String,
    value: String,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
