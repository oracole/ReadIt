package com.oracole.readit.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.data.DictEntry
import com.oracole.readit.data.MatchKind
import com.oracole.readit.ui.component.AlignedPair
import com.oracole.readit.ui.component.AdaptivePair
import com.oracole.readit.data.ai.CollocationResult
import com.oracole.readit.data.ai.ExampleResult
import com.oracole.readit.data.ai.PhoneticResult
import com.oracole.readit.data.ai.RootResult
import com.oracole.readit.data.ai.SenseResult

/** 卡片上四项可展开的内容 */
enum class SenseTab(val label: String) {
    SENSE("语境义"), ROOT("词根"), COLLO("搭配"), EXAMPLE("例句"), PHONETIC("音标")
}

/** 单项状态 */
sealed interface TabState {
    data object Idle : TabState
    data object Loading : TabState
    data class Ok(val data: Any) : TabState
    data class Err(val msg: String) : TabState
}

data class WordLookup(
    val word: String,
    /** 当前句，单句。用于界面显示「所在句」和存出处 */
    val sentence: String,
    /**
     * 上一句 + 当前句 + 下一句，只喂给 AI
     * 段首段尾没有相邻句时等于 sentence 本身
     *
     * 和 sentence 分开是有意的：混成一个的话
     * 卡片里的所在句和详情页的出处都会突然变成三句，界面会变乱
     */
    val context: String? = null,
    /**
     * 原形（running → run）。收藏按原形归一，所以是否已收藏
     * 必须用它来判断，用 word 的话，收过 run 再点 running 会显示未收藏
     */
    val baseForm: String? = null,
    val entry: DictEntry? = null,
    val loading: Boolean = true,
    val openTabs: Set<SenseTab> = emptySet(),
    val sense: TabState = TabState.Idle,
    val root: TabState = TabState.Idle,
    val collo: TabState = TabState.Idle,
    val example: TabState = TabState.Idle,
    val phonetic: TabState = TabState.Idle
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DefinitionSheet(
    lookup: WordLookup,
    readerTheme: ReaderTheme,
    collected: Boolean,
    toast: String? = null,
    onDismiss: () -> Unit,
    onSpeak: () -> Unit,
    onFavorite: () -> Unit,
    onTabClick: (SenseTab) -> Unit
) {
    val t = readerTheme
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cardAppear"
    )

    // 卡片内容可滚动时，手指下拉的意图通常是往回看而不是关掉
    // 只有内容已经在顶部，下拉才判定为关闭
    val scrollState = rememberScrollState()
    val atTop = remember { mutableStateOf(true) }
    LaunchedEffect(scrollState.value) { atTop.value = scrollState.value <= 0 }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            target != SheetValue.Hidden || atTop.value
        }
    )
    // 高度封顶：超过就滚动，而不是把卡片撑到看不见底
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
        // 高度的唯一决策者是这个内层 Column 的 animateContentSize
        // 外层只管滚动，绝不能再加第二个高度动画，否则拖到一半改高度会先塌再弹
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxH)
                .verticalScroll(scrollState)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .scale(0.96f + 0.04f * appear)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
            ) {
            // ---- 词头 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        lookup.word,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Serif,
                        color = t.text
                    )
                    lookup.entry?.phonetic?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("/$it/", style = MaterialTheme.typography.bodyMedium, color = t.secondary)
                    }
                }
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Outlined.VolumeUp, "发音", tint = t.secondary)
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (collected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (collected) "取消收藏" else "收藏",
                        tint = if (collected) MaterialTheme.colorScheme.primary
                        else t.secondary.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 词典释义 ----
            when {
                lookup.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("…", color = t.secondary)
                }

                lookup.entry != null -> {
                    if (lookup.entry.matchKind == MatchKind.LEMMA) {
                        Text(
                            "${lookup.entry.word} 的变形",
                            style = MaterialTheme.typography.labelMedium,
                            color = t.text.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(lookup.entry.translation, style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 26.sp, color = t.text)
                }

                else -> {
                    // 词典没有，但 AI 语境义通常已经给出了一份词典式释义
                    // 有就直接用，不再显示未收录占位，用户要的是意思，不是免责声明
                    val gloss = (lookup.sense as? TabState.Ok)
                        ?.data?.let { (it as? SenseResult)?.dictGloss }
                        ?.takeIf { it.isNotBlank() }
                    if (gloss != null) {
                        Text(gloss, style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 26.sp, color = t.text)
                    } else {
                        Text("词典可能未收录",
                            style = MaterialTheme.typography.bodyLarge, color = t.text)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "可能是人名、专有名词、新词或词组",
                            style = MaterialTheme.typography.bodyMedium,
                            color = t.secondary
                        )
                    }
                }
            }

            // ---- 四项 chip ----
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = t.text.copy(alpha = 0.12f))
            Spacer(Modifier.height(14.dp))

            // 用 FlowRow：宽度不够时换行，而不是把某个 chip 的文字压成两行
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SenseTab.entries.forEach { tab ->
                    TabChip(
                        tab = tab,
                        open = tab in lookup.openTabs,
                        state = lookup.stateOf(tab),
                        t = t,
                        onClick = { onTabClick(tab) }
                    )
                }
            }

            // ---- 展开的内容：块与块之间用短横线分隔 ----
            // 遍历 SenseTab.entries 而不是 openTabs，后者是 Set，顺序等于点击顺序
            // 全部条目常驻组合、只切 visible，这样 enter / exit 都能真正播放
            val openInOrder = SenseTab.entries.filter { it in lookup.openTabs }
            SenseTab.entries.forEach { tab ->
                val isOpen = tab in lookup.openTabs
                AnimatedVisibility(
                    visible = isOpen,
                    enter = expandVertically(
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                    ) + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        // 分隔线按在已展开序列中的位置决定，而不是枚举下标
                        if (openInOrder.indexOf(tab) > 0) {
                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(
                                Modifier.padding(horizontal = 8.dp),
                                color = t.text.copy(alpha = 0.14f)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.defaultMinSize(minHeight = 24.dp)) {
                            TabContent(tab, lookup.stateOf(tab), t)
                        }
                    }
                }
            }

            // ---- 卡片内轻提示（Snackbar 会被卡片挡住，放这里）----
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = t.text.copy(alpha = 0.08f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth()
                ) {
                    Text(
                        toast ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // ---- 所在句 ----
            if (lookup.sentence.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = t.text.copy(alpha = 0.12f))
                Spacer(Modifier.height(14.dp))
                Text(
                    lookup.sentence,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif
                    ),
                    color = t.secondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 22.sp
                )
            }
            }
        }
    }
}

fun WordLookup.stateOf(tab: SenseTab): TabState = when (tab) {
    SenseTab.SENSE -> sense
    SenseTab.ROOT -> root
    SenseTab.COLLO -> collo
    SenseTab.EXAMPLE -> example
    SenseTab.PHONETIC -> phonetic
}

/**
 * 把一个 tab 的状态写回，不改动 openTabs
 *
 * 展开与否由点击（onTabClick）决定，状态只负责内容
 * 早先这里无条件把 tab 加进 openTabs，后果是：
 * 用户点了一个正在加载的 tab 想收起来，请求返回时又把它强行展开
 * 看起来像"关不掉"
 */
fun WordLookup.withTab(tab: SenseTab, st: TabState): WordLookup = when (tab) {
    SenseTab.SENSE -> copy(sense = st)
    SenseTab.ROOT -> copy(root = st)
    SenseTab.COLLO -> copy(collo = st)
    SenseTab.EXAMPLE -> copy(example = st)
    SenseTab.PHONETIC -> copy(phonetic = st)
}

@Composable
private fun TabChip(
    tab: SenseTab,
    open: Boolean,
    state: TabState,
    t: ReaderTheme,
    onClick: () -> Unit
) {
    val isError = state is TabState.Err
    val isLoading = state is TabState.Loading
    val label = when {
        isLoading -> "${tab.label} ···"
        open -> "${tab.label} ▾"
        else -> tab.label
    }
    val bg by animateColorAsState(
        targetValue = if (open) t.text.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(180), label = "chipBg"
    )
    val fg by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error
            open -> t.text
            else -> t.secondary
        },
        animationSpec = tween(180), label = "chipFg"
    )
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error else t.text.copy(alpha = 0.28f)
        )
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            softWrap = false,
            // 上下左右统一 8dp：原来 AssistChip 的左右内边距偏大，占掉了本就不够的宽度
            // 左右 9dp：比 7dp 舒展一点，又不至于把音标挤到第二行
            // 展开态 chip 尾部会多一个 ▾（约 8dp），留的余量够
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun TabContent(tab: SenseTab, state: TabState, t: ReaderTheme) {
    Column {
        when (state) {
            is TabState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("…", color = t.secondary, style = MaterialTheme.typography.bodySmall)
            }

            is TabState.Err -> Text(
                state.msg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            is TabState.Ok -> when (tab) {
                SenseTab.SENSE -> (state.data as? SenseResult)?.let { SenseBody(it, t) }
                SenseTab.ROOT -> (state.data as? RootResult)?.let { RootBody(it, t) }
                SenseTab.COLLO -> (state.data as? CollocationResult)?.let { ColloBody(it, t) }
                SenseTab.EXAMPLE -> (state.data as? ExampleResult)?.let { ExampleBody(it, t) }
                SenseTab.PHONETIC -> (state.data as? PhoneticResult)?.let { PhoneticBody(it, t) }
            }

            TabState.Idle -> {}
        }
    }
}

@Composable
private fun PhoneticBody(r: PhoneticResult, t: ReaderTheme) {
    Column {
        if (r.uk.isNotBlank()) LabeledLine("英", "/${r.uk}/", t)
        if (r.us.isNotBlank() && r.us != r.uk) {
            Spacer(Modifier.height(4.dp))
            LabeledLine("美", "/${r.us}/", t)
        }
        if (r.low) {
            Spacer(Modifier.height(6.dp))
            Text("自动判断，仅供参考", style = MaterialTheme.typography.labelSmall,
                color = t.secondary.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun SenseBody(r: SenseResult, t: ReaderTheme) {
    Text(r.senseHere, style = MaterialTheme.typography.titleMedium, color = t.text, lineHeight = 24.sp)
    if (r.common.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        LabeledLine("常用义", r.common.joinToString(" · "), t)
    }
    if (r.note.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        LabeledLine("语感", r.note, t)
    }
    LowHint(r.low, t)
}

@Composable
private fun RootBody(r: RootResult, t: ReaderTheme) {
    r.parts.forEach { p ->
        // 左列不再固定 80dp：正常宽度不足 80dp 时按 80dp 对齐，行与行左边线一致；
        // 词根词缀本身很长时左列自己变宽，把释义往右顶；
        // 到 45% 封顶后才让左列折行，避免把右列挤没
        AlignedPair(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            minStartWidth = 80.dp,
            gap = 12.dp,
            start = {
                Text(p.part, style = MaterialTheme.typography.bodyMedium,
                    color = t.text, fontFamily = FontFamily.Monospace)
            },
            end = {
                Text(p.meaning, style = MaterialTheme.typography.bodyMedium, color = t.secondary)
            }
        )
    }
    if (r.memo.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        LabeledLine("记", r.memo, t)
    }
    if (r.origin.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(r.origin, style = MaterialTheme.typography.bodySmall, color = t.secondary, lineHeight = 19.sp)
    }
    LowHint(r.low, t)
}

@Composable
private fun ColloBody(r: CollocationResult, t: ReaderTheme) {
    r.items.forEach { it0 ->
        // 放得下并排，放不下换行。不能用 Row + weight，
        // 右侧中文一长，左侧英文会被压到只剩几个字符宽，一个字母一行竖排
        AdaptivePair(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            gap = 12.dp,
            left = {
                Text(
                    it0.phrase,
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.text,
                    fontFamily = FontFamily.Serif
                )
            },
            right = {
                Text(
                    it0.meaning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = t.secondary
                )
            }
        )
    }
    LowHint(r.low, t)
}

@Composable
private fun ExampleBody(r: ExampleResult, t: ReaderTheme) {
    r.items.forEachIndexed { i, e ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Text(e.en, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
            color = t.text, lineHeight = 21.sp)
        Spacer(Modifier.height(2.dp))
        Text(e.zh, style = MaterialTheme.typography.bodySmall, color = t.secondary, lineHeight = 19.sp)
    }
    LowHint(r.low, t)
}

@Composable
private fun LowHint(low: Boolean, t: ReaderTheme) {
    if (low) {
        Spacer(Modifier.height(8.dp))
        Text(
            "自动判断，仅供参考",
            style = MaterialTheme.typography.labelSmall,
            color = t.secondary.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun LabeledLine(label: String, value: String, t: ReaderTheme) {
    Row {
        Text(label, style = MaterialTheme.typography.labelMedium, color = t.secondary,
            modifier = Modifier.width(46.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = t.text, lineHeight = 21.sp)
    }
}
