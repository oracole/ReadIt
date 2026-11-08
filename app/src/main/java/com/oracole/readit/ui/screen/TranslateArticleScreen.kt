package com.oracole.readit.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一次全文翻译的完整参数。提到页面外保存，离开后再回来配置不丢。 */
data class TranslateRequest(
    val articleId: Long,
    /** 0 纯中文 / 1 句对照 / 2 段对照 */
    val mode: Int = 1,
    /** 文风索引：0 忠实 1 通顺 2 保留语气 3 自定义 */
    val styleIdx: Int = 0,
    val customStyle: String = "",
    /** 思考层级：0 关闭 1 低 2 中 3 高 */
    val thinkingIdx: Int = 0
)

private val STYLES = listOf("忠实原文", "通顺自然", "保留语气")
private val STYLE_DESC = listOf(
    "结构、语序尽量贴近原文",
    "像中文母语者写的，可大幅调整语序",
    "保留原文的语气、节奏和修辞"
)
private val THINK_LABELS = listOf("关闭", "低", "中", "高")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateArticleScreen(
    articleId: Long,
    title: String,
    wordCount: Int,
    paraCount: Int,
    initial: TranslateRequest? = null,
    translating: Boolean = false,
    progress: Pair<Int, Int>? = null,
    error: String? = null,
    onBack: () -> Unit,
    onStart: (TranslateRequest) -> Unit
) {
    var mode by remember { mutableIntStateOf(initial?.mode ?: 1) }
    var styleIdx by remember { mutableIntStateOf(initial?.styleIdx ?: 0) }
    var customStyle by remember { mutableStateOf(initial?.customStyle ?: "") }
    var thinkingIdx by remember { mutableIntStateOf(initial?.thinkingIdx ?: 0) }

    // 有字就锁住上面三项；没字就能选。不再有自定义单选项，
    // 那会造成死锁（选中后前三项全灰，回不去）
    val styleLocked = customStyle.isNotBlank()
    val styleAlpha by animateFloatAsState(if (styleLocked) 0.35f else 1f, tween(220), label = "sA")

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (translating && progress != null) {
                        Text(
                            // done 是已完成批数，显示成批号要 +1（当前正在跑第几批）
                            // 完成时 coerceAtMost 防止出现 6/5
                            "正在翻译 第 ${(progress.first + 1).coerceAtMost(progress.second)}/${progress.second} 批",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            onStart(
                                TranslateRequest(
                                    articleId = articleId,
                                    mode = mode,
                                    styleIdx = styleIdx,
                                    customStyle = customStyle,
                                    thinkingIdx = thinkingIdx
                                )
                            )
                        },
                        enabled = !translating,
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
                    ) {
                        if (translating) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("翻译中…")
                        } else {
                            Text("开始翻译")
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text("翻译全文", style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "约 $wordCount 词 · $paraCount 段",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(26.dp))
            SectionTitle("输出形式")
            listOf(
                "纯中文" to "只有译文，可当普通文章读",
                "句对照" to "英文句 + 中文句，逐句对照",
                "段对照" to "英文段 + 中文段"
            ).forEachIndexed { i, (name, desc) ->
                Row(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == i, onClick = { mode = i })
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            desc,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("文风")
            Column(Modifier.alpha(if (styleIdx == 3) 1f else 1f)) {
                STYLES.forEachIndexed { i, name ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = styleIdx == i,
                            enabled = !styleLocked,
                            onClick = { styleIdx = i }
                        )
                        Column(Modifier.weight(1f).alpha(if (styleLocked) 0.5f else 1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                STYLE_DESC[i],
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // 常驻输入框：填了就以它为准，上面的选项自动变灰
            OutlinedTextField(
                value = customStyle,
                onValueChange = { customStyle = it },
                placeholder = { Text("或自己写文风要求，例如：像科普杂志那样，句子短一点") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                minLines = 2
            )

            Spacer(Modifier.height(26.dp))
            SectionTitle("思考层级")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                THINK_LABELS.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = thinkingIdx == i,
                        onClick = { thinkingIdx = i },
                        shape = SegmentedButtonDefaults.itemShape(i, THINK_LABELS.size)
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "翻译任务对思考层级不敏感，建议保持关闭",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (error != null) {
                Spacer(Modifier.height(18.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}
