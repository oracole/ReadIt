package com.oracole.readit.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 选中整句后的卡片：查看 / 翻译 / 复制 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceSheet(
    sentence: String,
    readerTheme: ReaderTheme,
    translating: Boolean = false,
    translation: String? = null,
    note: String? = null,
    explaining: Boolean = false,
    explain: com.oracole.readit.data.ai.ExplainResult? = null,
    onSpeak: () -> Unit = {},
    onDismiss: () -> Unit,
    /** force = true 时跳过缓存，真正重新请求 */
    onTranslate: (Boolean) -> Unit = {},
    onExplain: (Boolean) -> Unit = {},
    onCopy: () -> Unit
) {
    val t = readerTheme
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "sentenceAppear"
    )
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f),
        containerColor = t.bg,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(t.secondary.copy(alpha = 0.4f), MaterialTheme.shapes.extraSmall)
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(scrollState)
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "整句",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.text.copy(alpha = 0.55f)
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.VolumeUp, "朗读整句",
                        modifier = Modifier.size(19.dp), tint = t.secondary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                sentence,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif),
                color = t.text,
                lineHeight = 27.sp,
                modifier = Modifier.scale(0.96f + 0.04f * appear)
            )

            // 译文
            AnimatedVisibility(
                visible = translation != null || translating,
                enter = expandVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = t.text.copy(alpha = 0.12f))
                    Spacer(Modifier.height(14.dp))
                    if (translating) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("翻译中…", style = MaterialTheme.typography.bodyMedium, color = t.secondary)
                        }
                    } else if (translation != null) {
                        Text(
                            translation,
                            style = MaterialTheme.typography.bodyLarge,
                            color = t.text,
                            lineHeight = 26.sp
                        )
                        if (!note.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = t.secondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = t.text.copy(alpha = 0.12f))
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    // 已有译文时再点就是重译，必须真的绕过缓存重新请求
                    // 否则按钮文字变了但结果没变，等于骗人
                    onClick = { onTranslate(translation != null) },
                    modifier = Modifier.weight(1f),
                    enabled = !translating,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = t.text.copy(alpha = 0.10f),
                        contentColor = t.text
                    )
                ) {
                    Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (translation == null) "翻译" else "重译")
                }
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = t.text)
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制")
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { onExplain(explain != null) },
                enabled = !explaining && translation != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (translation != null) t.text else t.secondary.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (explain == null) "AI 解析" else "重新解析")
            }

            // 解析结果：和翻译一样用长出来的方式出现
            AnimatedVisibility(
                visible = explain != null || explaining,
                enter = expandVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = t.text.copy(alpha = 0.06f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            AnimatedContent(
                                targetState = explaining,
                                transitionSpec = {
                                    fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                                },
                                label = "explainBody"
                            ) { loading ->
                            // AnimatedContent 的内容位置是 (0,0) 叠放，不是垂直排列
                            // 少了这个 Column，标题 point和正文 detail会压在同一个高度上
                            Column {
                            if (loading) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        Modifier.size(13.dp), strokeWidth = 2.dp,
                                        color = t.secondary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("解析中…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = t.secondary)
                                }
                            } else if (explain != null) {
                                if (explain.point.isNotBlank()) {
                                    Text(
                                        explain.point,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = t.text,
                                        lineHeight = 22.sp
                                    )
                                }
                                if (explain.detail.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        explain.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = t.secondary,
                                        lineHeight = 20.sp
                                    )
                                }
                                if (explain.low) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "自动判断，仅供参考",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = t.secondary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            }
                            }
                        }
                    }
                }
            }
        }
    }
}
