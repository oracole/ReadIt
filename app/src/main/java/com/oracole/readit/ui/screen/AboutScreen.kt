package com.oracole.readit.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.BuildConfig

/**
 * 关于页
 *
 * 署名分两块：
 *   协作，AI 参与了设计与实现（过程事实）
 * 运行依赖，App 运行时调用的第三方服务（运行事实）
 *
 * 不写 Co-authored-by：AI 不能承担法律责任。Linux 内核明确规定 AI 只能用
 * Assisted-by，美国版权局也要求作者必须是人
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                "ReadIt",
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Serif
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(36.dp))

            Section("作者")
            Item("oracole", "设计 开发 测试")

            Spacer(Modifier.height(28.dp))
            Section("协作")
            Item("deepseek-flash", "架构讨论 代码实现 问题排查")

            Spacer(Modifier.height(28.dp))
            Section("词典")
            // 不写死词条数。词库会随 ECDICT 更新换代，写死的话每次换库都要改这里
            Item("ECDICT", "离线英汉词典")
            Spacer(Modifier.height(6.dp))
            Text("MIT 协议", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            LinkText(
                "github.com/skywind3000/ECDICT",
                "https://github.com/skywind3000/ECDICT"
            )

            Spacer(Modifier.height(28.dp))
            Section("语音")
            Item("Android TTS", "系统语音引擎")

            Spacer(Modifier.height(28.dp))
            Section("数据")
            Text(
                "全部保存在本机\nAI 只处理选中的内容",
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "界面遵循 Material Design 3",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(36.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                "© 2026 oracole",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}

/** 可点的外链：主题色 + 下划线，点了走系统浏览器 */
@Composable
private fun LinkText(text: String, url: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    )
                )
            }
        }
    )
}

@Composable
private fun Item(name: String, desc: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(2.dp))
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
