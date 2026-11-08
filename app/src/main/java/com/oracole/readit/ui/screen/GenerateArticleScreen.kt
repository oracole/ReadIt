package com.oracole.readit.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.alpha
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一次生成请求的完整参数
 * 提到页面外保存，用户点完生成离开后再回来，配置不会丢
 */
data class GenRequest(
    val words: List<String>,
    val levelIdx: Int = 2,
    val length: Int = 400,
    val customLevelOn: Boolean = false,
    val customLevel: String = "",
    val customLength: String = "",
    val requirement: String = "",
    val topic: String = "",
    val blend: Int = 70,
    val thinkingIdx: Int = 0
)

/** CEFR 六档 */
private val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")
private val LEVEL_DESC = listOf("初学者", "基础", "中级", "中高级", "高级", "接近母语")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateArticleScreen(
    words: List<String>,
    initial: GenRequest? = null,
    generating: Boolean,
    error: String?,
    onBack: () -> Unit,
    onGenerate: (GenRequest) -> Unit
) {
    var levelIdx by remember { mutableIntStateOf(initial?.levelIdx ?: 2) }   // 默认 B1
    var length by remember { mutableFloatStateOf((initial?.length ?: 400).toFloat()) }
    // 难度：小开关切换自定义；篇幅：内联输入框，填了就以填的为准
    var customLevelOn by remember { mutableStateOf(initial?.customLevelOn ?: false) }
    var customLevel by remember { mutableStateOf(initial?.customLevel ?: "") }
    var customLength by remember { mutableStateOf(initial?.customLength ?: "") }
    var requirement by remember { mutableStateOf(initial?.requirement ?: "") }
    var topic by remember { mutableStateOf(initial?.topic ?: "") }
    // 融合度：值小=自然优先（可能弃词），值大=尽量全用上（可能割裂）
    var blend by remember { mutableFloatStateOf((initial?.blend ?: 70).toFloat()) }
    // 思考强度：0=关闭 1=低 2=中 3=高。默认关闭，生成文章不需要推理，开着只是更慢
    var thinkingIdx by remember { mutableIntStateOf(initial?.thinkingIdx ?: 0) }
    val THINK_LABELS = listOf("关闭", "低", "中", "高")

    // 预计写入词数：按幂函数衰减，比线性更接近实际（易融的词优先被用掉）
    fun estimateUsed(total: Int, b: Int): Int {
        if (total <= 0) return 0
        val r = (b / 100f).pow(0.85f)
        return (total * r).roundToInt().coerceIn(1, total)
    }

    val levelLocked = customLevelOn
    val lengthLocked = customLength.isNotBlank()
    // 滑块灰化的过渡：直接切 enabled 会生硬，用透明度渐变带一下
    val levelAlpha by animateFloatAsState(if (levelLocked) 0.35f else 1f, tween(220), label = "lvA")
    val lengthAlpha by animateFloatAsState(if (lengthLocked) 0.35f else 1f, tween(220), label = "lnA")

    // enableEdgeToEdge 后系统不再自动让位给输入法，必须手动处理 IME insets
    // 加在 Scaffold 上（而不是内容上），这样底部按钮也会一起被顶上来
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
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            onGenerate(
                                GenRequest(
                                    words = words,
                                    levelIdx = levelIdx,
                                    length = length.roundToInt(),
                                    customLevelOn = customLevelOn,
                                    customLevel = customLevel,
                                    customLength = customLength,
                                    requirement = requirement,
                                    topic = topic,
                                    blend = blend.roundToInt(),
                                    thinkingIdx = thinkingIdx
                                )
                            )
                        },
                        enabled = !generating,
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
                    ) {
                        if (generating) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在写…")
                        } else {
                            Text("生成")
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                "用 ${words.size} 个词生成文章",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Serif
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "从生词本重建语境，在上下文中再次遇见这些词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // ---- 目标词 ----
            SectionLabel("目标词")
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Text(
                        words.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 24.sp
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // ---- 难度 ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "难度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "自定义",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = customLevelOn,
                    onCheckedChange = { customLevelOn = it }
                )
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.alpha(levelAlpha)) {
                Slider(
                    value = levelIdx.toFloat(),
                    onValueChange = { levelIdx = it.roundToInt().coerceIn(0, 5) },
                    valueRange = 0f..5f,
                    steps = 4,
                    enabled = !levelLocked
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("A1", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("C2", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前：${LEVELS[levelIdx]} · ${LEVEL_DESC[levelIdx]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = customLevelOn,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customLevel,
                        onValueChange = { customLevel = it },
                        placeholder = { Text("例如：能读懂新闻标题，难以理解社论") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // ---- 篇幅 ----
            Text(
                "篇幅",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Column(Modifier.alpha(lengthAlpha)) {
                Slider(
                    value = length,
                    onValueChange = { length = (it / 50f).roundToInt() * 50f },
                    valueRange = 100f..1500f,
                    enabled = !lengthLocked
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${length.roundToInt()} 词 · 阅读约 ${
                        String.format("%.1f", length / 200f)
                    } 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 内联输入：直接写，写了就以它为准，上面的滑块自动变灰
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customLength,
                onValueChange = { customLength = it },
                placeholder = { Text("可自行填写篇幅要求") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(26.dp))
            HorizontalDivider()
            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = requirement,
                onValueChange = { requirement = it },
                label = { Text("写作要求") },
                placeholder = { Text("例如：第一人称叙述，对话为主") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("主题") },
                placeholder = { Text("例如：一次失败的创业") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(26.dp))
            HorizontalDivider()
            Spacer(Modifier.height(18.dp))

            // ---- 词汇融合度 ----
            Text(
                "词汇融合度",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Slider(
                value = blend,
                onValueChange = { blend = (it / 10f).roundToInt() * 10f },
                valueRange = 0f..100f
            )
            // 值小 = 少用词、文章更自然；值大 = 尽量全用上、可能割裂
            Row(Modifier.fillMaxWidth()) {
                Text("更自然", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("更完整", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "预计写入 ${estimateUsed(words.size, blend.roundToInt())} / ${words.size} 个词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(26.dp))

            // ---- 思考强度 ----
            Text(
                "思考强度",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
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
                "该任务对思考强度不敏感，建议保持关闭",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (error != null) {
                Spacer(Modifier.height(16.dp))
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}
