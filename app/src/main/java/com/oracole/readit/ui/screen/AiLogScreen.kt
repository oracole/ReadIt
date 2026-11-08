package com.oracole.readit.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.util.AiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 请求日志
 *
 * AI 出问题时最常见的三类：模型名失效、超时、返回被截断
 * 从界面上看不出来是哪一种，只有一个生成失败
 * 这里把每次请求的 system / user / 返回原文摊开，一眼能看出问题在哪
 *
 * 只读，不提供编辑。清空按钮是为了把已经有用的日志去掉，便于复现一次干净的问题
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiLogScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        text = withContext(Dispatchers.IO) { AiLog.readAll() }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("AI 请求日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.Delete, "清空")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    modifier = Modifier.weight(1f),
                    enabled = text.isNotBlank()
                ) { Text("复制全部") }
                OutlinedButton(
                    onClick = { scope.launch { reload() } },
                    modifier = Modifier.weight(1f)
                ) { Text("刷新") }
            }

            // 提示条数，方便判断是不是新记的
            Text(
                if (loading) "读取中…" else "共 ${text.count { it == '━' } / 3} 条记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            HorizontalDivider()

            if (!loading && text.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        "暂无记录。\n在阅读页点一个词，再回来看。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                Text(
                    text,
                    // 等宽字体：system / user / result 三段对齐才好读
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空日志？") },
            text = { Text("清空后旧的请求记录不可恢复。建议先复制一份。") },
            confirmButton = {
                TextButton(onClick = {
                    AiLog.clear()
                    confirmClear = false
                    scope.launch { reload() }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}
