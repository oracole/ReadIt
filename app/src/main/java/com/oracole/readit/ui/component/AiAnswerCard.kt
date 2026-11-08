package com.oracole.readit.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.data.ai.SearchAnswer

/**
 * AI 回答的展示
 *
 * 两种形态由数据决定，不靠调用方猜：
 *   isWord  → 词典条目：词头 / 音标 / 义项逐条（词性 + 释义 + 例句）/ 提示
 *   否则    → 普通回答：整段文本，段落之间靠空行分开
 *
 * 为什么不用一段字符串让 AI 自己排版：
 * 模型会把换行写成字面的 \\n，界面上就显示成 "\n1. \n2."。改成数组后排版由我们做
 */
@Composable
fun AiAnswerCard(
    answer: SearchAnswer,
    modifier: Modifier = Modifier,
    /** 字号基准：搜索页用正文，详情页可以略小 */
    compact: Boolean = false,
    /** 词头右侧的操作按钮（收藏）。传进来是为了和词头同一行、共基线。 */
    headAction: @Composable (() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        if (answer.isWord) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    answer.headword.ifBlank { "—" },
                    style = if (compact) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Serif,
                    modifier = Modifier.weight(1f)
                )
                headAction?.invoke()
            }
            if (answer.phonetic.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "/${answer.phonetic}/",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))

            answer.senses.forEachIndexed { i, line ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                // 连续同词性只在第一行标一次，后面的留空缩进对齐
                // 四个 v. 排下来会把标签变成噪音，反而看不出层次
                val prevPos = answer.senses.getOrNull(i - 1)?.pos.orEmpty()
                val showPos = line.pos.isNotBlank() && line.pos != prevPos
                Row {
                    if (showPos) {
                        Text(
                            line.pos,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(42.dp)
                        )
                    } else {
                        Spacer(Modifier.width(42.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            line.meaning,
                            style = if (compact) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp
                        )
                        if (line.example.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                line.example,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }

            if (answer.note.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        answer.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        } else {
            Text(
                answer.text,
                style = if (compact) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp
            )
        }

        if (answer.low) {
            Spacer(Modifier.height(12.dp))
            Text(
                "自动判断，仅供参考",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ─────────────────────────────────────────────
// 详情页四项的排版
// 和 AiAnswerCard 用同一套视觉规范：
//   标签/词性 → 主题色，小字
//   正文      → onSurface
//   例句/补充 → onSurfaceVariant，小一号
// ─────────────────────────────────────────────

@Composable
fun AiRootBlock(r: com.oracole.readit.data.ai.RootResult) {
    Column(Modifier.fillMaxWidth()) {
        r.parts.forEachIndexed { i, p ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            // 和词卡同一套：左列 78dp 起步，长了会变宽顶开释义
            AlignedPair(
                modifier = Modifier.fillMaxWidth(),
                minStartWidth = 78.dp,
                gap = 10.dp,
                start = {
                    Text(
                        p.part,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                end = {
                    Text(
                        p.meaning,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp
                    )
                }
            )
        }
        if (r.origin.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text("词源", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(r.origin, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
        if (r.memo.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("记忆", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(r.memo, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
        if (r.low) {
            Spacer(Modifier.height(10.dp))
            Text("自动判断，仅供参考", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

/**
 * 词形：一种词性一行，左边词性、中间原形、右边说明
 *
 * 不含释义，这个区块只回答它是什么词性、原形是哪个
 */
@Composable
fun AiFormBlock(
    r: com.oracole.readit.data.ai.FormResult,
    /** 词条本身的拼写。base 与它相同的条目属「原形自己」，不显示 */
    self: String = ""
) {
    // 只列变形。原形本身就是这个词的条目直接跳过，
    // 详情页顶部已写着这个词，释义区已给了它的释义，再列一遍是重复
    val rows = remember(r, self) {
        r.forms.filter { f ->
            self.isBlank() || !f.base.trim().equals(self.trim(), ignoreCase = true)
        }
    }
    if (rows.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, f ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (f.pos.isNotBlank()) {
                    Text(
                        f.pos,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(48.dp)
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Text(
                    f.base,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Serif
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (f.note.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        f.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (r.low) {
            Spacer(Modifier.height(10.dp))
            Text(
                "自动判断，仅供参考",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun AiColloBlock(r: com.oracole.readit.data.ai.CollocationResult) {
    Column(Modifier.fillMaxWidth()) {
        r.items.forEachIndexed { i, it ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            // 和词卡用同一套：放得下并排，放不下换行
            AdaptivePair(
                modifier = Modifier.fillMaxWidth(),
                gap = 10.dp,
                left = {
                    Text(
                        it.phrase,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                right = {
                    Text(
                        it.meaning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        if (r.low) {
            Spacer(Modifier.height(10.dp))
            Text("自动判断，仅供参考", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun AiExampleBlock(r: com.oracole.readit.data.ai.ExampleResult) {
    Column(Modifier.fillMaxWidth()) {
        r.items.forEachIndexed { i, it ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            Text(
                it.en,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                it.zh,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )
        }
        if (r.low) {
            Spacer(Modifier.height(10.dp))
            Text("自动判断，仅供参考", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun AiPhoneticBlock(r: com.oracole.readit.data.ai.PhoneticResult) {
    Column(Modifier.fillMaxWidth()) {
        if (r.uk.isNotBlank()) {
            Row {
                Text("英", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(28.dp))
                Text("/${r.uk}/", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (r.us.isNotBlank() && r.us != r.uk) {
            Spacer(Modifier.height(6.dp))
            Row {
                Text("美", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(28.dp))
                Text("/${r.us}/", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (r.low) {
            Spacer(Modifier.height(8.dp))
            Text("自动判断，仅供参考", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}
