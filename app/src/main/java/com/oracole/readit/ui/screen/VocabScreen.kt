package com.oracole.readit.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oracole.readit.ui.theme.Motion
import com.oracole.readit.data.VocabItem
import com.oracole.readit.ui.component.AiAnswerCard

private enum class Filter(val label: String) { ALL("全部"), LEARNING("生词"), KNOWN("熟词") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(
    items: List<VocabItem>,
    onOpenDetail: (VocabItem) -> Unit,
    onSetKnown: (VocabItem, Boolean) -> Unit,
    onRemove: (VocabItem) -> Unit,
    onRemoveBatch: (List<Long>) -> Unit,
    onSetKnownBatch: (List<Long>, Boolean) -> Unit,
    onCreateArticle: (List<VocabItem>) -> Unit,
    /** 查词：输入文本，返回释义文本（null = 未收录） */
    onLookup: suspend (String) -> String? = { null },
    /** 查词页里的 AI 追问，返回结构化回答 */
    onAiAsk: suspend (String) -> com.oracole.readit.data.ai.SearchAnswer? = { null },
    /** 已收藏的词（小写），用于结果区的收藏按钮状态 */
    collectedWords: Set<String> = emptySet(),
    /** 查词页就地收藏：词 + 释义 */
    onCollect: (word: String, translation: String) -> Unit = { _, _ -> },
    /** 是否处于多选。外层据此把 dock 换成多选操作条 */
    selecting: Boolean = false,
    onSelectingChange: (Boolean) -> Unit = {},
    /** 被选中的词 id。放外层是因为操作条要由外层渲染 */
    selected: MutableList<Long> = mutableStateListOf(),
    /** 筛选变化时同步给外层，操作条要按当前筛选决定显示哪几个按钮 */
    onFilterChange: (Int) -> Unit = {}
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // ---- 查词态 ----
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchKind by remember { mutableIntStateOf(0) }   // 0 词典 1 收藏 2 AI
    // query = 输入框里的字；submitted = 上一次提交查询的文本
    // 分开是因为回车才查：不能拿 query 直接过滤，否则等于实时搜索
    var submitted by remember { mutableStateOf("") }
    // 三个类型各存各的结果。切换 tab 时不清空，
    // 否则查完词典切到 AI 再切回来结果就没了，得重新敲一次回车
    // 结果本身就是缓存的，重新请求不花钱，但用户要多按一下，这是多余的摩擦
    var lookupResult by remember { mutableStateOf<String?>(null) }
    var lookupQuery by remember { mutableStateOf("") }   // 这份结果对应的词
    var lookupMiss by remember { mutableStateOf(false) }
    var looking by remember { mutableStateOf(false) }
    var aiAnswer by remember {
        mutableStateOf<com.oracole.readit.data.ai.SearchAnswer?>(null)
    }
    var aiQuery by remember { mutableStateOf("") }
    var aiAsking by remember { mutableStateOf(false) }
    val searchScope = rememberCoroutineScope()

    // 搜索页收藏列表的顺序快照
    //
    // 为什么需要：词汇列表按 (isKnown, updatedAt) 排序，标熟会改变 isKnown
    // 那行会立刻跳到熟词区。用户正看着某一行点标熟，结果它跳走了，很难受
    // 所以在进入搜索时拍一张顺序表，之后只按这张表排，位置固定
    // 退出搜索时清空，下次进来重新拍
    var orderSnapshot by remember { mutableStateOf<Map<Long, Int>?>(null) }
    LaunchedEffect(searchActive) {
        orderSnapshot = if (searchActive) {
            items.withIndex().associate { (i, v) -> v.id to i }
        } else null
    }

    /** 提交查询。三类共用：回车或点放大镜触发。 */
    fun submitSearch() {
        val q = query.trim()
        if (q.isEmpty() || looking || aiAsking) return
        submitted = q
        when (searchKind) {
            0 -> {
                looking = true; lookupResult = null; lookupMiss = false
                lookupQuery = q
                searchScope.launch {
                    val r = onLookup(q)
                    looking = false
                    if (r == null) lookupMiss = true else lookupResult = r
                }
            }
            2 -> {
                aiAsking = true; aiAnswer = null
                aiQuery = q
                searchScope.launch {
                    aiAnswer = onAiAsk(q)
                    aiAsking = false
                }
            }
            else -> Unit  // 收藏是本地过滤，提交只更新 submitted
        }
    }

    fun exitSearch() {
        // 按顺序而不是并发：AnimatedVisibility 会同时播两个方向的动画
        searchActive = false
        query = ""
        lookupResult = null
        lookupQuery = ""
        lookupMiss = false
        aiAnswer = null
        aiQuery = ""
        submitted = ""
        searchKind = 0
    }

    /**
     * 叉号与系统返回键共用的行为：
     *   有内容 → 先清空，停在搜索态
     *   没内容 → 退出搜索，回词汇列表
     *
     * 抽出来是为了两边完全一致。之前返回键没被拦截
     * 会走到系统的"再按一次退出"
     */
    fun onCloseOrBack() {
        if (query.isNotEmpty() || submitted.isNotEmpty()) {
            query = ""
            submitted = ""
            lookupResult = null
            lookupQuery = ""
            lookupMiss = false
            aiAnswer = null
            aiQuery = ""
            looking = false
            aiAsking = false
        } else {
            exitSearch()
        }
    }

    // 搜索态下拦截返回键，行为和叉号一致
    androidx.activity.compose.BackHandler(enabled = searchActive) { onCloseOrBack() }

    // 多选状态由外层传入，底部栏要能在dock和多选操作条之间二选一
    // 这只有外层知道。放在这里的话，两个底栏会叠在一起
    fun exitSelect() { onSelectingChange(false); selected.clear() }


    // 多选状态下，系统返回先退出多选
    androidx.activity.compose.BackHandler(enabled = selecting) { exitSelect() }

    // 初值同步给外层：操作条要按当前筛选决定显示标熟还是生词
    LaunchedEffect(filter) { onFilterChange(filter) }

    val learning = items.filter { !it.isKnown }
    val known = items.filter { it.isKnown }
    val shown = when (Filter.entries[filter]) {
        Filter.ALL -> items
        Filter.LEARNING -> learning
        Filter.KNOWN -> known
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(if (selected.isEmpty()) "选择单词" else "已选 ${selected.size}") },
                    navigationIcon = {
                        IconButton(onClick = { exitSelect() }) {
                            Icon(Icons.Filled.Close, "退出多选")
                        }
                    }
                )
            } else {
                MediumTopAppBar(title = { Text("词汇") }, scrollBehavior = scrollBehavior)
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // ---- 搜索框 ----
            // 收起：一个完整的胶囊输入框
            // 展开：左边输入框（收窄）+ 右边查询按钮（横向展开出来）
            // 两者等高同圆角；按钮出现用 expandHorizontally，宽度是动画出来的
            if (!selecting) {
                val barHeight = 46.dp
                val barShape = RoundedCornerShape(23.dp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .height(barHeight)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                barShape
                            )
                            // 带上 shape，否则涟漪是方形、会溢出圆角
                            .clip(barShape)
                            .clickable(enabled = !searchActive) { searchActive = true }
                            .padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 放大镜只做装饰，和收起态一致，不再兼作提交键
                        Icon(
                            Icons.Filled.Search, null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        if (searchActive) {
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                    MaterialTheme.colorScheme.primary
                                ),
                                decorationBox = { inner ->
                                    if (query.isEmpty()) {
                                        Text(
                                            "搜索单词或输入问题…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            // 同一个按钮两个作用：
                            //   有文字 → 只清文字（不退出搜索）
                            //   没文字 → 退出搜索
                            // 这样不需要第二个按钮，也符合先退出再退出页面的直觉顺序
                            IconButton(
                                onClick = { onCloseOrBack() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close, "退出搜索",
                                    Modifier.size(17.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                "搜索单词或输入问题…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 查询按钮：只在展开态出现
                    // 宽度动画 + 淡入，让"输入框收窄、按钮长出来"成为一个连续动作
                    AnimatedVisibility(
                        visible = searchActive,
                        enter = expandHorizontally(
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            expandFrom = Alignment.Start
                        ) + fadeIn(),
                        exit = shrinkHorizontally(
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            shrinkTowards = Alignment.Start
                        ) + fadeOut()
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { submitSearch() },
                                enabled = query.isNotBlank() && !looking && !aiAsking,
                                shape = barShape,
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                modifier = Modifier.height(barHeight).width(76.dp)
                            ) {
                                Text("查询", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            // 内容区：两个状态叠加在同一个 Box 里
            // 必须用 Box 而不是 Column，Column 里两个 AnimatedVisibility 是上下排列
            // 退出时高度会立刻塌陷，看不到滑出动画
            Box(Modifier.fillMaxWidth().weight(1f)) {

            // ---- 搜索态 ----
            androidx.compose.animation.AnimatedVisibility(
                visible = searchActive && !selecting,
                modifier = Modifier.fillMaxSize(),
                // 起点是搜索框底边：高度从 0 长出来，内容贴着顶部不动
                // 所以视觉上是从搜索框下沿往下展开，而不是整块从上方滑下来
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    expandFrom = Alignment.Top
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(),
                label = "searchPanel"
            ) {
                Column(Modifier.fillMaxSize()) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        listOf("词典", "收藏", "AI").forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = searchKind == i,
                                onClick = {
                                    // 只切类型，不清任何结果，
                                    // 每个类型的结果各自保留，切回来直接看见
                                    if (searchKind != i) searchKind = i
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, 3)
                            ) { Text(label) }
                        }
                    }
                    // 用 weight 占满剩余高度，否则内部 verticalScroll 没有高度约束
                    // 内容超过一屏时滚不动
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        // 类型切换时左右滑动：方向按索引大小决定
                        // 从词典(0)到 AI(2) 是新页从右进、旧页左移，反过来则相反
                        AnimatedContent(
                            targetState = searchKind,
                            transitionSpec = {
                                val dir = if (targetState > initialState) 1 else -1
                                val sp = Motion.spatialNoBounce<androidx.compose.ui.unit.IntOffset>()
                                val fade = Motion.effects<Float>()
                                (slideInHorizontally(sp) { w -> dir * w / 3 } + fadeIn(fade))
                                    .togetherWith(
                                        slideOutHorizontally(sp) { w -> -dir * w / 3 } + fadeOut(fade)
                                    )
                            },
                            label = "searchKind"
                        ) { kind ->
                        SearchResultArea(
                            kind = kind,
                            query = query,
                            submitted = submitted,
                            looking = looking,
                            lookupResult = lookupResult,
                            lookupQuery = lookupQuery,
                            lookupMiss = lookupMiss,
                            aiAnswer = aiAnswer,
                            aiQuery = aiQuery,
                            aiAsking = aiAsking,
                            vocabItems = items,
                            orderSnapshot = orderSnapshot,
                            onOpenDetail = onOpenDetail,
                            onSetKnown = onSetKnown,
                            collectedWords = collectedWords,
                            onCollect = onCollect
                        )
                        }
                    }
                }
            }

            // ---- 浏览态 ----
            androidx.compose.animation.AnimatedVisibility(
                visible = !searchActive,
                modifier = Modifier.fillMaxSize(),
                enter = slideInVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow),
                    initialOffsetY = { it }
                ) + fadeIn(),
                exit = slideOutVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow),
                    targetOffsetY = { it }
                ) + fadeOut()
            ) {
            Column(Modifier.fillMaxWidth()) {
            if (!selecting) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    listOf(
                        "全部 ${items.size}",
                        "生词 ${learning.size}",
                        "熟词 ${known.size}"
                    ).forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = filter == i,
                            onClick = { filter = i; onFilterChange(i) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)
                        ) { Text(label) }
                    }
                }
            }

            AnimatedContent(
                targetState = filter,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    val sp = Motion.spatialNoBounce<androidx.compose.ui.unit.IntOffset>()
                    val fade = Motion.effects<Float>()
                    (slideInHorizontally(sp) { w -> dir * w / 3 } + fadeIn(fade))
                        .togetherWith(
                            slideOutHorizontally(sp) { w -> -dir * w / 3 } + fadeOut(fade)
                        )
                },
                label = "vocabFilter"
            ) { f ->
                val list = when (Filter.entries[f]) {
                    Filter.ALL -> items
                    Filter.LEARNING -> learning
                    Filter.KNOWN -> known
                }
                if (list.isEmpty()) {
                    EmptyState(Filter.entries[f], Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(list, key = { it.id }) { v ->
                            Box(Modifier.animateItem()) {
                            VocabRow(
                                item = v,
                                selecting = selecting,
                                selected = selected.contains(v.id),
                                onClick = {
                                    if (selecting) {
                                        // 用 removeAll，不是 remove。
                                        // selected 是 List，同一个 id 可能被加过两次，
                                        // 只删第一个的话复选框还亮着，但计数已经减一
                                        if (selected.contains(v.id)) selected.removeAll { it == v.id }
                                        else if (!selected.contains(v.id)) selected.add(v.id)
                                    } else onOpenDetail(v)
                                },
                                onLongClick = {
                                    if (!selecting) {
                                        onSelectingChange(true)
                                        // 先清再加，避免上一次残留的选中项混进来
                                        selected.clear()
                                        selected.add(v.id)
                                    }
                                },
                                onToggleKnown = { onSetKnown(v, !v.isKnown) }
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

@Composable
private fun EmptyState(f: Filter, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Outlined.BookmarkBorder,
            null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            when (f) {
                Filter.ALL -> "还没有收藏的单词"
                Filter.LEARNING -> "没有生词了 🎉"
                Filter.KNOWN -> "还没有标记为熟词的单词"
            },
            style = MaterialTheme.typography.titleSmall
        )
        if (f == Filter.ALL) {
            Spacer(Modifier.height(6.dp))
            Text(
                "阅读时点任意单词，在卡片上收藏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VocabRow(
    item: VocabItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleKnown: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.displayWord,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif
                    )
                    item.phonetic?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "/$it/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.oneLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!selecting) {
                val bg by animateColorAsState(
                    targetValue = if (item.isKnown) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.Transparent,
                    animationSpec = tween(200), label = "chipBg"
                )
                val fg by animateColorAsState(
                    targetValue = if (item.isKnown) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(200), label = "chipFg"
                )
                AssistChip(
                    onClick = onToggleKnown,
                    label = {
                        Text(
                            if (item.isKnown) "熟词" else "标熟",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(containerColor = bg, labelColor = fg),
                    border = if (item.isKnown) null else AssistChipDefaults.assistChipBorder(enabled = true)
                )
            }
        }
    }
}

/**
 * 搜索结果区
 *
 * 三种类型共用一个区域：
 * 0 词典，内嵌释义（不弹窗，这是查词页，弹窗反而多一层）
 * 1 收藏，实时过滤本地词汇
 * 2 AI，自由提问，走 DeepSeek
 */
/**
 * 搜索结果区。不含输入框，输入框只有一个，就是顶部那个搜索框
 * （之前这里又放了一个 OutlinedTextField，导致页面上出现两个输入框。）
 */
@Composable
private fun SearchResultArea(
    kind: Int,
    query: String,
    submitted: String,
    looking: Boolean,
    lookupResult: String?,
    lookupQuery: String,
    lookupMiss: Boolean,
    aiAnswer: com.oracole.readit.data.ai.SearchAnswer?,
    aiQuery: String,
    aiAsking: Boolean,
    vocabItems: List<VocabItem>,
    orderSnapshot: Map<Long, Int>?,
    onOpenDetail: (VocabItem) -> Unit,
    onSetKnown: (VocabItem, Boolean) -> Unit,
    collectedWords: Set<String>,
    onCollect: (word: String, translation: String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        when (kind) {
            0 -> {
                when {
                    lookupQuery.isBlank() -> Hint(
                        "输入一个单词，按回车查询"
                    )
                    looking -> Loading("查询中…")
                    lookupMiss -> Hint("词典里没有「$lookupQuery」\n可以切到「AI」问一下")
                    lookupResult != null -> Column(
                        Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                lookupQuery,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Serif,
                                modifier = Modifier.weight(1f)
                            )
                            CollectIcon(
                                collected = lookupQuery.lowercase() in collectedWords,
                                onClick = { onCollect(lookupQuery, lookupResult) }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            lookupResult,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 26.sp
                        )
                    }
                }
            }

            1 -> {
                // 收藏是本地过滤，即时、零成本，所以按输入框里的字实时过滤
                val q = query.trim()
                val matched = if (q.isBlank()) vocabItems
                else vocabItems.filter {
                    it.displayWord.contains(q, ignoreCase = true) ||
                        it.translation.contains(q, ignoreCase = true)
                }
                // 按进入搜索时的顺序排，标熟不会让这一行跳走
                val shown = orderSnapshot?.let { snap ->
                    matched.sortedBy { snap[it.id] ?: Int.MAX_VALUE }
                } ?: matched
                if (shown.isEmpty()) {
                    Hint(if (q.isBlank()) "还没有收藏的单词" else "没有匹配「$q」的收藏")
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(shown, key = { it.id }) { v ->
                            VocabRow(
                                item = v,
                                selecting = false,
                                selected = false,
                                onClick = { onOpenDetail(v) },
                                onLongClick = { },
                                onToggleKnown = { onSetKnown(v, !v.isKnown) }
                            )
                        }
                    }
                }
            }

            else -> {
                when {
                    aiAsking -> Loading("思考中…")
                    aiAnswer != null -> {
                        val a = aiAnswer
                        Column(
                            Modifier.fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp)
                        ) {
                            // 收藏按钮通过 headAction 传进卡片，和词头同一行、共基线
                            // 之前它单独占一行放在卡片上方，导致词头被压到按钮下面
                            // 视觉上不在一条线上
                            val head = a.headword.ifBlank { aiQuery }
                            AiAnswerCard(
                                answer = a,
                                headAction = if (a.isWord) {
                                    {
                                        CollectIcon(
                                            collected = head.lowercase() in collectedWords,
                                            onClick = { onCollect(head, aiAnswerToPlain(a)) }
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                    aiQuery.isBlank() -> Hint(
                        "输入任何问题，按回车发送\n比如「used to 和 be used to 有什么区别」"
                    )
                    else -> Hint("没有拿到回答\n再按一次查询试试")
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Loading(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 收藏小按钮
 * 词典和 AI 两个结果区共用，查完就地收藏，不用点进详情页
 */
@Composable
private fun CollectIcon(collected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            if (collected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            if (collected) "取消收藏" else "收藏",
            modifier = Modifier.size(20.dp),
            tint = if (collected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 把词典式回答压成一段可存的释义（收藏用） */
private fun aiAnswerToPlain(a: com.oracole.readit.data.ai.SearchAnswer): String =
    com.oracole.readit.data.ai.senseSummary(a.senses).ifBlank { a.text }

/**
 * 多选时的底部操作条。替换掉 dock，而不是压在它上面
 *
 * 由外层渲染：底栏该显示 dock 还是这个条，只有外层知道
 * 放在 VocabScreen 内部的话两个底栏会同时出现
 */
@Composable
fun VocabSelectionBar(
    filter: Int,
    selectedCount: Int,
    onSetKnownBatch: (List<Long>, Boolean) -> Unit,
    onRemoveBatch: (List<Long>) -> Unit,
    onCreateArticle: () -> Unit,
    selectedIds: List<Long>
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cp = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                val cur = Filter.entries[filter]
                // 生词页只给标熟，熟词页只给退回，全部页两个都给
                if (cur != Filter.KNOWN) {
                    FilledTonalButton(
                        onClick = { onSetKnownBatch(selectedIds, true) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = cp
                    ) {
                        Text("标熟", maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (cur != Filter.LEARNING) {
                    FilledTonalButton(
                        onClick = { onSetKnownBatch(selectedIds, false) },
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        contentPadding = cp
                    ) {
                        Text("生词", maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                OutlinedButton(
                    onClick = { onRemoveBatch(selectedIds) },
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = cp,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除", maxLines = 1, softWrap = false,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCreateArticle,
                enabled = selectedCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) { Text("用这 $selectedCount 个词生成文章") }
        }
    }
}
