package com.oracole.readit.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.data.DictRepository
import com.oracole.readit.data.VocabEntry
import com.oracole.readit.data.VocabListImporter
import com.oracole.readit.data.VocabParseResult
import com.oracole.readit.data.VocabRepository
import com.oracole.readit.util.Notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 词汇表导入
 *
 * 四个阶段：选文件 → 预览 → 导入 → 结果
 * 阶段之间直接切换，不做交叉淡入淡出，那种效果会让两份内容同时画在同一位置
 * 每块卡片出现用 expandVertically + spring
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabImportScreen(
    dictRepo: DictRepository,
    vocabRepo: VocabRepository,
    aiConfigured: Boolean,
    /** 用词典式提示词拿一条释义，失败返回 null */
    aiMeaning: suspend (String) -> String?,
    onBack: () -> Unit,
    onImported: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // 词条处理方式
    var mode by remember { mutableIntStateOf(MODE_SKIP) }
    // 导入后标为生词还是熟词
    var asKnown by remember { mutableStateOf(false) }

    // 粘贴导入的文本。和选文件走同一条解析链路，只是文本已在内存里
    var inputText by remember { mutableStateOf("") }

    var parsing by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<VocabParseResult.Ok?>(null) }
    var preview by remember { mutableStateOf<PreviewData?>(null) }

    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var stopRequested by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            parsing = true
            parsed = null
            preview = null
            report = null
            mode = MODE_SKIP
            when (val r = VocabListImporter.read(ctx, uri)) {
                is VocabParseResult.Ok -> {
                    // 先分类再赋值：parsed 一旦非空，起始页就收起
                    // 若此时 preview 还是 null，中间会闪一屏空白
                    preview = classify(r.entries, dictRepo)
                    parsed = r
                }
                is VocabParseResult.Err -> Notify.show(r.message)
            }
            parsing = false
        }
    }

    fun parsePasted() {
        val t = inputText
        if (t.isBlank() || parsing || running) return
        // 收起键盘，否则解析出的预览区被键盘挡住
        keyboard?.hide()
        scope.launch {
            parsing = true
            parsed = null
            preview = null
            report = null
            mode = MODE_SKIP
            when (val r = VocabListImporter.parseText(t)) {
                is VocabParseResult.Ok -> {
                    preview = classify(r.entries, dictRepo)
                    parsed = r
                }
                is VocabParseResult.Err -> Notify.show(r.message)
            }
            parsing = false
        }
    }

    fun startImport() {
        val p = preview ?: return
        val file = parsed ?: return
        val rows = mutableListOf<VocabRepository.ImportRow>()
        val needAi = mutableListOf<String>()
        var skipped = 0

        when (mode) {
            MODE_AI -> {
                p.dictCovered.forEach { rows += VocabRepository.ImportRow(it.word, it.meaning, 0) }
                // 选了 AI 就全部走 AI，文件里原有的释义一概不用，
                // 用户要的是 AI 给的释义，不是文件里那一行
                (p.fileOnly + p.noMeaning).forEach { needAi += it.word }
            }
            MODE_FILE -> {
                p.dictCovered.forEach { rows += VocabRepository.ImportRow(it.word, it.meaning, 0) }
                p.fileOnly.forEach { rows += VocabRepository.ImportRow(it.word, it.meaning, 0) }
                skipped = p.noMeaning.size
            }
            else -> {
                p.dictCovered.forEach { rows += VocabRepository.ImportRow(it.word, it.meaning, 0) }
                skipped = p.fileOnly.size + p.noMeaning.size
            }
        }

        stopRequested = false
        running = true
        val status = if (asKnown) 1 else 0
        total = needAi.size
        done = 0

        scope.launch {
            var aiOk = 0
            var aiFail = 0
            val failed = mutableListOf<String>()

            if (needAi.isNotEmpty()) {
                for ((i, w) in needAi.withIndex()) {
                    if (stopRequested) break
                    val m = if (aiConfigured) aiMeaning(w) else null
                    if (m.isNullOrBlank()) {
                        aiFail++
                        if (failed.size < 50) failed += w
                    } else {
                        rows += VocabRepository.ImportRow(w, m, status)
                        aiOk++
                    }
                    done = i + 1
                }
                // 中途停止：还没轮到的按跳过计
                if (stopRequested && aiFail + aiOk < needAi.size) {
                    skipped += needAi.size - aiOk - aiFail
                }
            }

            val tally = if (rows.isEmpty()) {
                VocabRepository.ImportTally(0, 0)
            } else {
                vocabRepo.importBatch(rows.map {
                    // 统一用开始时选的状态
                    it.copy(status = status)
                })
            }
            running = false
            onImported()

            report = buildReport(
                file = file, tally = tally, skipped = skipped + tally.skippedExisting,
                aiRequested = needAi.size, aiOk = aiOk, aiFail = aiFail,
                failed = failed, mode = mode
            )
        }
    }

    Scaffold(
        // 有输入框，键盘弹出时必须避让，否则输入区被盖住
        modifier = Modifier.imePadding(),
        // 底部必须带上导航栏 inset，否则最后一个按钮会被手势条压住
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top)
            .union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        topBar = {
            TopAppBar(
                title = { Text("导入词汇表") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !running) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            if (parsed == null && !parsing && report == null) {
                IntroBlock(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onPasteFromClipboard = {
                        val t = clipboard.getText()?.text
                        if (t.isNullOrBlank()) Notify.show("剪贴板没有文本")
                        else inputText = t
                    },
                    onParseText = { parsePasted() },
                    onPickFile = { picker.launch(SUPPORTED_MIME) }
                )
            }

            if (parsing) {
                Row(
                    Modifier.padding(vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在解析…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ---- 预览 ----
            AnimatedVisibility(
                visible = preview != null && report == null,
                enter = expandVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    val p = preview
                    val f = parsed
                    if (p != null && f != null) {
                        Spacer(Modifier.height(4.dp))
                        FileLine(f)
                        Spacer(Modifier.height(14.dp))
                        CountCard(p)
                        Spacer(Modifier.height(14.dp))

                        if (p.notInDictTotal > 0) {
                            Text(
                                "词典未收录 ${p.notInDictTotal} 个",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            p.samples.forEach { w ->
                                Text(
                                    w,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Serif
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                            if (p.notInDictTotal > p.samples.size) {
                                Text(
                                    "另有 ${p.notInDictTotal - p.samples.size} 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            ModeOption(
                                selected = mode == MODE_AI,
                                enabled = aiConfigured,
                                title = "由 AI 生成释义",
                                trailing = if (aiConfigured) "${p.noMeaning.size} 个" else "需配置密钥",
                                onClick = { mode = MODE_AI }
                            )
                            ModeOption(
                                selected = mode == MODE_FILE,
                                enabled = p.fileOnly.isNotEmpty(),
                                title = "采用文件内释义",
                                trailing = if (p.fileOnly.isEmpty()) "无" else "${p.fileOnly.size} 个",
                                onClick = { mode = MODE_FILE }
                            )
                            ModeOption(
                                selected = mode == MODE_SKIP,
                                enabled = true,
                                title = "不保留",
                                trailing = "跳过 ${p.notInDictTotal} 个",
                                onClick = { mode = MODE_SKIP }
                            )
                        } else {
                            Text(
                                "全部词条词典均已收录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { asKnown = !asKnown }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("导入为熟词", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "默认导入为生词",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = asKnown, onCheckedChange = { asKnown = it })
                        }

                        Spacer(Modifier.height(18.dp))
                        val incoming = when (mode) {
                            MODE_AI -> p.dictCovered.size + p.fileOnly.size + p.noMeaning.size
                            MODE_FILE -> p.dictCovered.size + p.fileOnly.size
                            else -> p.dictCovered.size
                        }
                        Button(
                            onClick = { startImport() },
                            enabled = !running && incoming > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (mode == MODE_AI && p.notInDictTotal > 0)
                                    "导入 $incoming 个词 · 需 ${p.notInDictTotal} 次 AI 请求"
                                else "导入 $incoming 个词"
                            )
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }
            }

            // ---- 进度 ----
            AnimatedVisibility(
                visible = running,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(Modifier.padding(top = 20.dp)) {
                    val target = if (total <= 0) 1f else done.toFloat() / total
                    val anim by animateFloatAsState(
                        targetValue = target,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "importProgress"
                    )
                    LinearProgressIndicator(
                        progress = { anim },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (total <= 0) "正在写入…" else "正在生成释义  $done/$total",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (total > 0 && done < total) {
                            TextButton(onClick = { stopRequested = true }) { Text("停止") }
                        }
                    }
                }
            }

            // ---- 结果 ----
            AnimatedVisibility(
                visible = report != null,
                enter = expandVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text("导入完成", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            report.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                            lineHeight = 20.sp
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(
                                    androidx.compose.ui.text.AnnotatedString(report.orEmpty())
                                )
                                Notify.show("报告已复制")
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("复制报告") }
                        Button(
                            onClick = {
                                parsed = null; preview = null; report = null
                                done = 0; total = 0
                                inputText = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("再导入一份") }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }

            if (parsed == null && !parsing && report == null) Spacer(Modifier.height(40.dp))
        }
    }
}

private const val MODE_AI = 0
private const val MODE_FILE = 1
private const val MODE_SKIP = 2

private val SUPPORTED_MIME = arrayOf(
    "text/plain", "text/csv", "text/comma-separated-values",
    "application/csv", "text/tab-separated-values", "*/*"
)

private data class PreviewData(
    val dictCovered: List<VocabEntry>,
    val fileOnly: List<VocabEntry>,
    val noMeaning: List<VocabEntry>,
    val samples: List<String>
) {
    val notInDictTotal: Int get() = fileOnly.size + noMeaning.size
}

/**
 * 把词条分三类：词典有、词典没有但文件有、两边都没有
 * 全部走 exactTranslation，不做词形还原，和查词页的行为一致
 */
private suspend fun classify(
    entries: List<VocabEntry>,
    dict: DictRepository
): PreviewData = withContext(Dispatchers.IO) {
    val a = ArrayList<VocabEntry>()
    val b = ArrayList<VocabEntry>()
    val c = ArrayList<VocabEntry>()
    for (e in entries) {
        val dictTr = dict.exactTranslation(e.word)?.takeIf { it.isNotBlank() }
        when {
            dictTr != null -> a += VocabEntry(e.word, dictTr)
            e.meaning.isNotBlank() -> b += e
            else -> c += e
        }
    }
    PreviewData(
        dictCovered = a,
        fileOnly = b,
        noMeaning = c,
        samples = (b + c).map { it.word }.take(10)
    )
}

@Composable
private fun IntroBlock(
    text: String,
    onTextChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onParseText: () -> Unit,
    onPickFile: () -> Unit
) {
    // 只统计非空行，和实际会被解析的行数一致
    val lineCount = remember(text) {
        if (text.isBlank()) 0 else text.lines().count { it.isNotBlank() }
    }

    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "支持纯文本、CSV、TSV、由 Anki 导出的 txt\n" +
                "每行一个词条，释义位于第二列，释义非必填\n" +
                "编码自动识别，支持 UTF-8 与 GBK\n" +
                "分隔符自动判断",
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("粘贴词表") },
            placeholder = {
                // 用逗号示例：和实际要填的形状一致
                // 空格分隔的话，解析时会被当成整行一个词，然后因为含中文被剔除
                Text("abandon,放弃", style = MaterialTheme.typography.bodyMedium)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 8,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (lineCount > 0) "共 $lineCount 行" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPasteFromClipboard) { Text("从剪贴板粘贴") }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onParseText,
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("解析文本") }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("从文件导入")
        }
    }
}

@Composable
private fun FileLine(f: VocabParseResult.Ok) {
    Text(
        // 粘贴的文本没有编码信息，过滤掉空项
        listOf(f.fileName, f.encoding, "${f.delimiter}分隔")
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun CountCard(p: PreviewData) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            CountRow("词典已收录", p.dictCovered.size)
            CountRow("文件自带释义", p.fileOnly.size)
            CountRow("词典未收录", p.noMeaning.size)
        }
    }
}

@Composable
private fun CountRow(label: String, n: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            "$n",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    trailing: String,
    onClick: () -> Unit
) {
    val fg = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = fg, modifier = Modifier.weight(1f))
        Text(
            trailing,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else fg
        )
    }
}

private fun buildReport(
    file: VocabParseResult.Ok,
    tally: VocabRepository.ImportTally,
    skipped: Int,
    aiRequested: Int,
    aiOk: Int,
    aiFail: Int,
    failed: List<String>,
    mode: Int
): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    return buildString {
        appendLine("ReadIt 词汇表导入报告")
        appendLine("时间 $time")
        appendLine(
            listOf("来源 ${file.fileName}", file.encoding, "${file.delimiter}分隔")
                .filter { it.isNotBlank() }
                .joinToString("，")
        )
        appendLine("解析 ${file.entries.size} 条")
        appendLine(
            when (mode) {
                MODE_AI -> "方式 由 AI 生成释义"
                MODE_FILE -> "方式 采用文件内释义"
                else -> "方式 不保留未收录词"
            }
        )
        appendLine()
        appendLine("新增 ${tally.inserted}")
        appendLine("跳过 $skipped")
        if (aiRequested > 0) {
            appendLine("AI 请求 $aiRequested 次，成功 $aiOk，失败 $aiFail")
        }
        if (file.badLines.isNotEmpty()) {
            appendLine()
            appendLine("无法识别的行：")
            file.badLines.forEach { appendLine("  $it") }
        }
        if (failed.isNotEmpty()) {
            appendLine()
            appendLine("释义获取失败的词：")
            appendLine("  " + failed.joinToString("、"))
        }
    }
}
