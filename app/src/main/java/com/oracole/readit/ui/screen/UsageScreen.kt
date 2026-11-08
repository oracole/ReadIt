package com.oracole.readit.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.data.ai.TabUsage
import com.oracole.readit.data.ai.UsageRecord
import com.oracole.readit.data.ai.UsageSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAB_SENSE = "SENSE"
private const val TAB_ROOT = "ROOT"
private const val TAB_COLLO = "COLLO"
private const val TAB_EXAMPLE = "EXAMPLE"
private const val TAB_PHONETIC = "PHONETIC"

private fun tabLabel(t: String): String = when (t) {
    TAB_SENSE -> "语境义"
    TAB_ROOT -> "词根"
    TAB_COLLO -> "搭配"
    TAB_EXAMPLE -> "例句"
    TAB_PHONETIC -> "音标"
    else -> t
}

/** 微元 -> 元字符串。小于 0.01 元时多给两位有效数字，避免一律显示 ¥0.00 */
private fun yuan(micro: Long): String {
    val v = micro / 1_000_000.0
    return when {
        v <= 0.0 -> "¥0.00"
        v < 0.01 -> String.format(Locale.US, "¥%.5f", v)
        v < 1.0 -> String.format(Locale.US, "¥%.3f", v)
        else -> String.format(Locale.US, "¥%.2f", v)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    summary: UsageSummary,
    onBack: () -> Unit,
    onClear: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    val empty = summary.count == 0

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("用量统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (!empty) {
                        TextButton(onClick = { confirmClear = true }) { Text("清空") }
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
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- 本月总览 ----
            Text(
                "本月",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    // 大数字是总 token，精确到个位带千分位
                    // 之前这里是估算费用，换了服务以后那个数就不是真实花费
                    Text(
                        exact(summary.tokens),
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Serif
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "总 token",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        StatCell("调用", "${summary.count}", Modifier.weight(1f))
                        StatCell("输入", formatTokens(summary.promptTokens), Modifier.weight(1f))
                        StatCell("输出", formatTokens(summary.completionTokens), Modifier.weight(1f))
                        StatCell(
                            "缓存命中",
                            if (summary.cacheRate > 0f)
                                "${(summary.cacheRate * 100).toInt()}%"
                            else "—",
                            Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "估算费用：" + yuan(summary.costMicro),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    // 这一行永远按 deepseek-flash 的价格算，不随当前配置的服务变
                    // 换服务后这个数只代表按 DeepSeek 计价大约多少钱，不是实际花费
                    Text(
                        "按 deepseek-flash 高峰价，下同",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (empty) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "还没有查询记录。\n在阅读页点开单词，选一项 AI 查询，这里就会有数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            } else {
                // ---- 按功能分 ----
                Spacer(Modifier.height(24.dp))
                Text(
                    "按功能",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                summary.byTab.forEach { t ->
                    TabBar(t, summary.byTab.maxOf { it.count })
                }

                // ---- 最近记录 ----
                Spacer(Modifier.height(24.dp))
                Text(
                    "最近",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                summary.recent.forEach { r ->
                    RecentRow(r)
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空所有用量记录？") },
            text = { Text("只删除统计数据，不影响收藏的词和缓存。") },
            confirmButton = {
                TextButton(onClick = {
                    onClear(); confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Serif
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TabBar(t: TabUsage, maxCount: Int) {
    val frac = if (maxCount <= 0) 0f else t.count.toFloat() / maxCount
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tabLabel(t.tab),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${t.count} 次 · ${formatTokens(t.tokens)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                yuan(t.costMicro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
            )
        }
    }
}

@Composable
private fun RecentRow(r: UsageRecord) {
    val fmt = remember { SimpleDateFormat("M-d HH:mm", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(tabLabel(r.tab), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "${fmt.format(Date(r.at))} · ${formatTokens(r.totalTokens)} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            yuan(r.costMicro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// token 数缩写
// 输入输出这两个数字通常很大，用 k/M/B 读起来快
// 总 token 另外用 exact()，那个要精确到个位
private fun formatTokens(n: Int): String = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1_000_000_000.0)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/** 精确到个位，带千分位 */
private fun exact(n: Int): String = String.format(Locale.US, "%,d", n)
