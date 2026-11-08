package com.oracole.readit.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oracole.readit.data.DictRepository
import com.oracole.readit.data.DictState
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictSettingsScreen(
    repo: DictRepository,
    onBack: () -> Unit,
    /** 词典状态变了就通知上层 —— 否则返回设置页还显示「未导入」，要重进 App 才刷新 */
    onStateChanged: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<DictState>(DictState.NotImported) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { state = repo.state() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importing = true
            progress = 0f
            message = null
            scope.launch {
                val r = repo.import(uri) { progress = it }
                importing = false
                message = if (r.isSuccess) "导入完成" else (r.exceptionOrNull()?.message ?: "导入失败")
                state = repo.state()
                onStateChanged()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("词典") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(20.dp)
        ) {
            // 当前词库
            when (val s = state) {
                is DictState.Ready -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("ECDICT 英汉词典", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${fmtCount(s.wordCount)} 词条 · ${fmtSize(s.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                is DictState.NotImported -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("尚未导入词典", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "导入后即可离线查词，无需联网",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                is DictState.Corrupt -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "词库文件异常：${s.reason}",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (importing) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "正在导入 ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state is DictState.Ready) "更换词典" else "导入词典文件") }

                Spacer(Modifier.height(8.dp))
                Text(
                    "选择 ReadIt 词库文件（.db），导入后只在本机使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            message?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            if (state is DictState.Ready && !importing) {
                Spacer(Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                TextButton(
                    onClick = { confirmClear = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("移除词典") }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            Text(
                "词库来源：ECDICT（MIT 协议）。\n词条含音标、中文释义、词形变化，仅保留纯字母与常用词组。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("移除词典？") },
            text = { Text("移除后无法离线查词，已收藏的单词不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        repo.clear()
                        state = DictState.NotImported
                        onStateChanged()
                    }
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

private fun fmtCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.0f 万", n / 10000.0)
    n >= 10000 -> String.format(Locale.US, "%.0f 万", n / 10000.0)
    else -> "$n"
}

private fun fmtSize(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", b / 1073741824.0)
    b >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", b / 1048576.0)
    else -> "${b / 1024} KB"
}
