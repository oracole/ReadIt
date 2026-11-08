package com.oracole.readit.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.data.VocabItem
import com.oracole.readit.ui.component.AiRootBlock
import com.oracole.readit.ui.component.AiColloBlock
import com.oracole.readit.ui.component.AiExampleBlock
import com.oracole.readit.ui.component.AiPhoneticBlock
import com.oracole.readit.ui.component.AiFormBlock
import com.oracole.readit.ui.component.AiAnswerCard
import androidx.compose.material.icons.outlined.ContentCopy
import com.oracole.readit.util.Notify
import com.oracole.readit.util.formatRelative

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun VocabDetailScreen(
    item: VocabItem,
    onBack: () -> Unit,
    onToggleKnown: () -> Unit,
    onRemove: () -> Unit,
    onSpeak: () -> Unit,
    onOpenContext: (articleId: Long, sentence: String, word: String) -> Unit,
    /** 问 AI：传入问题，返回回答；null 表示失败 */
    /** 四项返回 String；自由输入返回 SearchAnswer */
    onAsk: suspend (question: String) -> Any? = { null },
    /** 读已存的词形（JSON）。没有返回 null */
    loadForms: suspend (word: String) -> String? = { null },
    /** 请求词形并落库，返回解析结果 */
    fetchForms: suspend (word: String) -> com.oracole.readit.data.ai.FormResult? = { null },
    /** 读这个词的问 AI 历史，最新的在前 */
    loadAsks: suspend (word: String)
        -> List<com.oracole.readit.data.VocabRepository.AskRecord> = { emptyList() },
    /** 存一条问答，返回新记录 id */
    addAsk: suspend (word: String, question: String, answer: String) -> Long =
        { _, _, _ -> 0L },
    /** 删一条 */
    deleteAsk: suspend (id: Long) -> Unit = { },
    /** 是否显示「词形」区块。关闭时不请求也不显示 */
    showForms: Boolean = false,
    /** 有没有配 AI 密钥。没配时词形请求必失败，提示要区分开 */
    aiReady: Boolean = true
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // 问 AI
    var asking by remember { mutableStateOf(false) }
    var askInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 词形：打开就自动读缓存，没有才请求。长期保存，清缓存不丢
    var forms by remember { mutableStateOf<com.oracole.readit.data.ai.FormResult?>(null) }
    var formsLoading by remember { mutableStateOf(false) }
    LaunchedEffect(item.displayWord, showForms) {
        // 开关关掉就完全不请求，它和按原形收藏解决的是同一件事
        // 都开着是重复，白花钱
        if (!showForms) return@LaunchedEffect
        val cached = loadForms(item.displayWord)
        if (cached != null) {
            forms = com.oracole.readit.data.ai.parseForms(cached)
        } else {
            formsLoading = true
            forms = fetchForms(item.displayWord)
            formsLoading = false
            // 失败要说一声：否则开关打开后区块不出现，看起来像开关没生效
            // 两种原因分开说，没配密钥时稍后重试永远重试不成功
            if (forms == null) {
                Notify.show(
                    if (!aiReady) "词形需要 AI 密钥，先去设置里配上"
                    else "词形获取失败，可稍后重试"
                )
            }
        }
    }

    // 问答历史：最新的在最前，默认全部折叠
    var asks by remember { mutableStateOf<List<com.oracole.readit.data.VocabRepository.AskRecord>>(emptyList()) }
    var expandedAsk by remember { mutableStateOf<Long?>(null) }
    var pendingDeleteAsk by remember {
        mutableStateOf<com.oracole.readit.data.VocabRepository.AskRecord?>(null)
    }
    LaunchedEffect(item.displayWord) { asks = loadAsks(item.displayWord) }

    fun runAsk(q: String) {
        if (q.isBlank() || asking) return
        asking = true
        scope.launch {
            val r = onAsk(q)
            // 拿到回答就落库。存的是渲染后的纯文本
            // 下次进来直接显示，不用重新解析结构化对象
            val text = askToPlain(r)
            if (text != null) {
                // addAsk 返回新记录 id，用它把这条展开，
                // 刚问完就该看到答案，不该再点一下
                val newId = addAsk(item.displayWord, askLabel(q), text)
                asks = loadAsks(item.displayWord)
                expandedAsk = newId
            } else {
                // 失败不入库，给个提示，静默失败会让人以为没反应
                Notify.show("没有拿到回答，请稍后再试")
            }
            asking = false
        }
    }

    // edge-to-edge 下键盘不会自动避让，必须显式加，否则问 AI 的输入框被盖住
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
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
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
            // 词头
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // FlowRow：复制按钮紧跟词尾，宽度不够时自己换到下一行
                    // 用 Row 的话长词组会把按钮挤出屏幕
                    FlowRow(verticalArrangement = Arrangement.Center) {
                        Text(
                            item.displayWord,
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(item.displayWord))
                                Notify.show("已复制")
                            },
                            // 图标本身视觉重心偏上，往下压 1.5dp 才和文字齐
                            modifier = Modifier.size(40.dp).offset(y = 1.5.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy, "复制单词",
                                modifier = Modifier.size(19.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item.phonetic?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "/$it/",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Outlined.VolumeUp, "发音",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 见过的其他形态。收藏 went 时归到 go 那条，这里把 went 列出来
            // 否则用户会疑惑我明明收的是 went，怎么变成 go 了
            if (item.formList.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已收藏：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        item.formList.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Serif
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                item.translation,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 27.sp
            )
            // 释义下方一个圆角按钮，靠左。释义是多行文字，纯图标不好认
            // 带文字更明确；左对齐和正文起头对齐
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = {
                    clipboard.setText(AnnotatedString(item.translation))
                    Notify.show("已复制释义")
                },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "复制释义",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- 词形 ----
            // 和问 AI独立的区块：词形是确定性查询，查一次就存住
            // 不像问答会累积历史，所以不放 chip 里也不需要交互
            val formRows = forms?.forms?.filter {
                !it.base.trim().equals(item.displayWord.trim(), ignoreCase = true)
            }.orEmpty()
            if (showForms && (formsLoading || formRows.isNotEmpty())) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "词形",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (formsLoading) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(
                            Modifier.size(12.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 整块复制：词性 + 原形，一行一个
                    forms?.takeIf { formRows.isNotEmpty() }?.let { f ->
                        IconButton(
                            onClick = {
                                // 带词性：同一个词有多个词性时，只给原形分不清哪个是哪个
                                val txt = f.forms
                                    .filter {
                                        !it.base.trim()
                                            .equals(item.displayWord.trim(), ignoreCase = true)
                                    }
                                    .joinToString("\n") { x ->
                                        if (x.pos.isBlank()) x.base
                                        else "${x.pos}  ${x.base}"
                                    }
                                clipboard.setText(AnnotatedString(txt))
                                Notify.show("已复制")
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy, "复制词形",
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                forms?.let { AiFormBlock(it, self = item.displayWord) }
            }

            // ---- 问 AI ----
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                "问 AI",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            // 四项和释义卡片的 chip 一一对应，点一下直接出结果
            // 传入的是 tab 标识，由上层决定用哪条提示词
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ROOT" to "词根",
                    "DERIVE" to "派生",
                    "COLLO" to "搭配",
                    "EXAMPLE" to "例句",
                    "PHONETIC" to "音标"
                ).forEach { (key, label) ->
                    AssistChip(
                        onClick = { runAsk(key) },
                        enabled = !asking,
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = askInput,
                    onValueChange = { askInput = it },
                    placeholder = { Text("或输入你想问的…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = {
                        runAsk(askInput)
                        askInput = ""
                    },
                    enabled = askInput.isNotBlank() && !asking
                ) { Text("问") }
            }

            // 历史：默认全部折叠，点一下展开，长按删除
            // 存的是渲染后的纯文本，所以直接显示，不用再走一遍结构化组件
            if (asking || asks.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                if (asking) {
                    Row(
                        Modifier.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(13.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "思考中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                asks.forEach { rec ->
                    val open = expandedAsk == rec.id
                    // 间距必须放外层 Column 的 Spacer 上，不能放进 Surface 的 modifier
                    // 放进 modifier 的话 padding 会排到 clip 前面
                    // 裁剪区域和 Surface 实际绘制区域差 8dp，涟漪在圆角处对不齐
                    Column(Modifier.padding(bottom = 8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 点击必须写在 Surface 里面：
                        // 写在外层 modifier 时，涟漪在 Surface 背景之下，被完全盖住；
                        // 放进内容里，涟漪才会被 Surface 的圆角裁到
                        val interaction = remember { MutableInteractionSource() }
                        Column(
                            Modifier
                                .combinedClickable(
                                    interactionSource = interaction,
                                    // 涟漪半径必须显式给死
                                    // 不自带时按按下那一刻的尺寸算半径，卡片展开后
                                    // 半径不变就盖不到四角。Google issue 183019123 记录了
                                    // 这个问题，官方建议就是指定一个足够大的固定半径
                                    // 半径要给到展开后从点击点到最远角的距离：
                                    // 卡片展开约 200dp 高、310dp 宽，最远角约 184dp
                                    // 取 250dp 留余量
                                    indication = ripple(radius = 250.dp),
                                    onClick = { expandedAsk = if (open) null else rec.id },
                                    onLongClick = { pendingDeleteAsk = rec }
                                )
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    rec.question,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (open) 3 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (open) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    rec.answer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 24.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                // 整块复制
                                Text(
                                    "长按可删除",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    }
                }
            }

            // 状态
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("状态", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp))
                Text(
                    if (item.isKnown) "熟词" else "生词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.isKnown) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = onToggleKnown) {
                    Text(if (item.isKnown) "退回生词" else "标为熟词")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text("收藏于", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp))
                Text(formatRelative(item.createdAt), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 出处
            // collect() 每次都会写一条 vocab_context（查词页收藏写的是空句子）
            // 只有词汇表导入的词一条都没有。所以这里为空就说明来源是导入
            if (item.contexts.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(
                    "从词汇表导入",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(
                    "出处（${item.contexts.size} 处）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                item.contexts.forEach { c ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        // 跳回原文用当时点的那个形态，不是原形
// 收藏 running 归到 run 之后，item.displayWord 是 run
// 但原文里写的是 running，用 run 去定位会找不到
onClick = {
    c.articleId?.let {
        onOpenContext(it, c.sentence, c.wordForm ?: item.displayWord)
    }
}
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            // 查词页收藏没有出处句子，sentence 是空串
                            // 直接渲染会出现一张空卡片，所以给个说明
                            if (c.sentence.isBlank()) {
                                Text(
                                    "在查词页收藏",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    c.sentence,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic
                                    ),
                                    lineHeight = 22.sp
                                )
                            }
                            c.articleTitle?.let { title ->
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.MenuBook, null, Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "《$title》",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 历史删除确认
    pendingDeleteAsk?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAsk = null },
            title = { Text("删除这条记录？") },
            text = { Text(rec.question, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    val id = rec.id
                    pendingDeleteAsk = null
                    if (expandedAsk == id) expandedAsk = null
                    scope.launch {
                        deleteAsk(id)
                        asks = loadAsks(item.displayWord)
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAsk = null }) { Text("取消") }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个单词？") },
            text = { Text("收藏记录和 ${item.contexts.size} 处出处会一起删除。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onRemove() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

}

/** tab key 转成人话，存历史时用 */
private fun askLabel(key: String): String = when (key) {
    "ROOT" -> "词根"
    "DERIVE" -> "派生"
    "COLLO" -> "搭配"
    "EXAMPLE" -> "例句"
    "PHONETIC" -> "音标"
    else -> key
}

/**
 * 把结构化回答压成纯文本，用于存历史
 * 存文本而不是 JSON：历史只用来回看，不需要再解析成组件
 */
private fun askToPlain(r: Any?): String? = when (r) {
    null -> null
    is String -> r.takeIf { it.isNotBlank() }
    is com.oracole.readit.data.ai.RootResult -> buildString {
        r.parts.forEach { append(it.part).append("  ").append(it.meaning).append('\n') }
        if (r.origin.isNotBlank()) append('\n').append(r.origin)
        if (r.memo.isNotBlank()) append('\n').append(r.memo)
    }.trim().takeIf { it.isNotBlank() }
    is com.oracole.readit.data.ai.DerivationResult ->
        // 一个派生词一行；词性跟在词形后面用空格隔开，不用分号
        r.items.joinToString("\n") { x ->
            if (x.pos.isBlank()) {
                if (x.meaning.isBlank()) x.word else "${x.word}  ${x.meaning}"
            } else {
                if (x.meaning.isBlank()) "${x.word}  ${x.pos}"
                else "${x.word}  ${x.pos}  ${x.meaning}"
            }
        }.takeIf { it.isNotBlank() }
    is com.oracole.readit.data.ai.CollocationResult ->
        r.items.joinToString("\n") { "${it.phrase}  ${it.meaning}" }.takeIf { it.isNotBlank() }
    is com.oracole.readit.data.ai.ExampleResult ->
        r.items.joinToString("\n\n") { "${it.en}\n${it.zh}" }.takeIf { it.isNotBlank() }
    is com.oracole.readit.data.ai.PhoneticResult -> buildString {
        if (r.uk.isNotBlank()) append("英 /").append(r.uk).append("/\n")
        if (r.us.isNotBlank() && r.us != r.uk) append("美 /").append(r.us).append("/")
    }.trim().takeIf { it.isNotBlank() }
    is com.oracole.readit.data.ai.AskResult ->
        r.answer.takeIf { it.isNotBlank() }
    is com.oracole.readit.data.ai.SearchAnswer ->
        // 和词表、收藏走同一套归并规则，否则同一条回答在列表里是n. 甲；乙
        // 在历史里是两行n. 甲 / n. 乙，同一个人看到两种排版
        if (r.isWord) com.oracole.readit.data.ai.senseSummary(r.senses)
            .takeIf { it.isNotBlank() }
        else r.text.takeIf { it.isNotBlank() }
    else -> null
}
