package com.oracole.readit.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oracole.readit.ui.theme.Motion
import com.oracole.readit.data.Article
import com.oracole.readit.util.formatRelative
import com.oracole.readit.util.formatWords

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    articles: List<Article>,
    learningCount: Int,
    knownCount: Int,
    onImportClick: () -> Unit,
    onPaste: () -> Unit,
    onOpen: (Article) -> Unit,
    onToggleFavorite: (Article) -> Unit,
    onRename: (Article, String) -> Unit,
    onDelete: (Article) -> Unit,
    onFavoriteBatch: (Set<Long>, Boolean) -> Unit,
    onDeleteBatch: (Set<Long>) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var menuTarget by remember { mutableStateOf<Article?>(null) }
    var renameTarget by remember { mutableStateOf<Article?>(null) }
    var deleteTarget by remember { mutableStateOf<Article?>(null) }
    var onlyFavorite by rememberSaveable { mutableStateOf(false) }

    // 多选
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    fun exitSelect() { selecting = false; selected.clear() }


    androidx.activity.compose.BackHandler(enabled = selecting) { exitSelect() }

    val favorited = articles.filter { it.isFavorite }
    val recent = remember(articles) {
        articles.filter { it.lastReadAt != null }
            .sortedByDescending { it.lastReadAt }.take(8)
    }
    val finishedCount = articles.count { it.isFinished || it.progressPercent >= 95 }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = {
                        Text(if (selected.isEmpty()) "选择文章" else "已选 ${selected.size} 篇")
                    },
                    navigationIcon = {
                        IconButton(onClick = { exitSelect() }) {
                            Icon(Icons.Filled.Close, "退出多选")
                        }
                    }
                )
            } else {
                MediumTopAppBar(title = { Text("书架") }, scrollBehavior = scrollBehavior)
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selecting,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow),
                    expandFrom = Alignment.Bottom
                ) + fadeIn(),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow),
                    shrinkTowards = Alignment.Bottom
                ) + fadeOut()
            ) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val cp = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                        FilledTonalButton(
                            onClick = { onFavoriteBatch(selected.toSet(), true) },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            contentPadding = cp
                        ) { Text("收藏", maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.labelLarge) }
                        FilledTonalButton(
                            onClick = { onFavoriteBatch(selected.toSet(), false) },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            contentPadding = cp
                        ) { Text("取消收藏", maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.labelLarge) }
                        OutlinedButton(
                            onClick = { confirmBatchDelete = true },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            contentPadding = cp,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("删除", maxLines = 1, softWrap = false,
                            style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        },
        floatingActionButton = {
            if (articles.isNotEmpty() && !selecting) {
                FloatingActionButton(onClick = onImportClick) {
                    Icon(Icons.Filled.Add, contentDescription = "导入文章")
                }
            }
        }
    ) { pad ->
        if (articles.isEmpty()) {
            EmptyShelf(onImportClick, onPaste, Modifier.padding(pad))
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
            ) {
                StatsBar(
                    articleCount = articles.size,
                    finishedCount = finishedCount,
                    favoriteCount = favorited.size,
                    learningCount = learningCount
                )

                if (recent.isNotEmpty()) {
                    SectionTitle("最近阅读")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        items(recent, key = { it.id }) { a -> RecentCard(a) { onOpen(a) } }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !onlyFavorite,
                        onClick = { onlyFavorite = false },
                        label = { Text("全部 ${articles.size}") }
                    )
                    FilterChip(
                        selected = onlyFavorite,
                        onClick = { onlyFavorite = true },
                        label = { Text("收藏 ${favorited.size}") }
                    )
                }

                // 全部 / 收藏切换。和底部 tab、词汇页的筛选保持同一套参数：
                // 位移宽度三分之一，位移走 spatial 弹簧，透明度走 effects
                AnimatedContent(
                    targetState = onlyFavorite,
                    transitionSpec = {
                        val dir = if (targetState) 1 else -1
                        val sp = Motion.spatialNoBounce<androidx.compose.ui.unit.IntOffset>()
                        val fade = Motion.effects<Float>()
                        (slideInHorizontally(sp) { w -> dir * w / 3 } + fadeIn(fade))
                            .togetherWith(
                                slideOutHorizontally(sp) { w -> -dir * w / 3 } + fadeOut(fade)
                            )
                    },
                    label = "shelfFilter"
                ) { favOnly ->
                    val list = if (favOnly) favorited else articles
                    Column {
                        Text(
                            if (favOnly) "收藏文章（${list.size}）" else "全部文章（${list.size}）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
                        )
                        if (list.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "还没有收藏的文章",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            list.forEach { a ->
                                ArticleCard(
                                    a = a,
                                    selecting = selecting,
                                    checked = a.id in selected,
                                    onClick = {
                                        if (selecting) {
                                            if (a.id in selected) selected.remove(a.id)
                                            else selected.add(a.id)
                                        } else onOpen(a)
                                    },
                                    onLongClick = { if (!selecting) menuTarget = a },
                                    onToggleFavorite = { onToggleFavorite(a) }
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    menuTarget?.let { target ->
        ArticleActionSheet(
            article = target,
            onDismiss = { menuTarget = null },
            onOpen = { menuTarget = null; onOpen(target) },
            onRename = { menuTarget = null; renameTarget = target },
            onToggleFavorite = { menuTarget = null; onToggleFavorite(target) },
            onDelete = { menuTarget = null; deleteTarget = target },
            onSelect = {
                menuTarget = null
                exitSelect()
                selecting = true
                selected.add(target.id)
            }
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle -> onRename(target, newTitle); renameTarget = null }
        )
    }

    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("删除这 ${selected.size} 篇文章？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteBatch(selected.toSet())
                    confirmBatchDelete = false
                    exitSelect()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这篇文章？") },
            text = { Text("《${target.title}》将从书架移除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(target); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun StatsBar(
    articleCount: Int,
    finishedCount: Int,
    favoriteCount: Int,
    learningCount: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "文章 $articleCount · 已读 $finishedCount · 收藏 $favoriteCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "生词 $learningCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun RecentCard(a: Article, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.width(150.dp).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                a.title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Serif,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 用 minHeight 而不是固定 height：固定值在两行文字超过 40dp 时
                // 会把第二行裁掉一半（字体缩放调大就会遇到）
                modifier = Modifier.heightIn(min = 40.dp)
            )
            Spacer(Modifier.height(6.dp))
            // 用官方组件，保留 stop indicator
            //
            // 那个 4dp 小点是 Material 3 的规定设计，不是 bug：
            // 当轨道与容器的对比度低于 3:1 时，它是无障碍必需的，
            // 否则低对比度下看不出轨道到哪里结束
            // 我们的轨道用 surfaceVariant、卡片用 surface，正是低对比度场景
            // 所以这个点应该留着
            LinearProgressIndicator(
                progress = { a.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${a.progressPercent}% · ${formatWords(a.wordCount)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleCard(
    a: Article,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            // 上下留 18dp。14dp 时标题的字身几乎贴着卡片边缘
            // 视觉上显得拥挤；18dp 让文字四周都有呼吸空间
            // 又不至于高到看起来像空卡片
            Modifier.padding(start = 16.dp, top = 18.dp, bottom = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onClick() })
                Spacer(Modifier.width(2.dp))
            }
            Column(Modifier.weight(1f)) {
                // 两行：文章标题通常比词组长得多，一行几乎必然截断
                // 列表里不做滚动，一屏几十个标题同时滚会像故障，也干扰扫读
                Text(
                    a.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(formatWords(a.wordCount))
                        when {
                            a.isFinished || a.progressPercent >= 95 -> append(" · 已读完")
                            a.progressPercent > 0 -> append(" · 已读 ${a.progressPercent}%")
                            else -> append(" · 未读")
                        }
                        a.lastReadAt?.let { append(" · ${formatRelative(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!selecting) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (a.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        if (a.isFavorite) "取消收藏" else "收藏",
                        tint = if (a.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleActionSheet(
    article: Article,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                article.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
            )
            HorizontalDivider(Modifier.padding(bottom = 8.dp))
            val normal = MaterialTheme.colorScheme.onSurface
            MenuRow("打开", Icons.Outlined.MenuBook, normal, onOpen)
            MenuRow(
                if (article.isFavorite) "取消收藏" else "收藏",
                if (article.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                normal,
                onToggleFavorite
            )
            MenuRow("重命名", Icons.Outlined.Edit, normal, onRename)
            MenuRow("多选", Icons.Outlined.Checklist, normal, onSelect)
            MenuRow("删除", Icons.Outlined.Delete, MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun MenuRow(
    text: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, label = { Text("标题") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifBlank { initial }) },
                enabled = text.isNotBlank()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun EmptyShelf(onImport: () -> Unit, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.MenuBook, contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(24.dp))
        Text("书架是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入一篇英文文章，点击任意单词查看释义",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        FilledTonalButton(
            onClick = onImport,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
        ) { Text("选择 TXT 文件") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onPaste) { Text("粘贴文本") }
    }
}
