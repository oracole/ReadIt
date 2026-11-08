package com.oracole.readit.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.oracole.readit.data.Settings
import com.oracole.readit.data.ai.AiResult
import com.oracole.readit.data.ai.DeepSeekClient
import com.oracole.readit.data.ai.ErrorKind
import kotlinx.coroutines.launch

// API 接口配置
// 默认连 DeepSeek，只填密钥就能用
// 换别的服务时把名称、地址、路径、模型四项一起改
// 地址和路径分两个框，不合并成整条 URL
// OpenAI 的 /v1 在地址里、路径还是 /chat/completions
// 合起来用户反而要自己拆
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    settings: Settings,
    onBack: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiResult?>(null) }
    var elapsed by remember { mutableStateOf(0L) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var nameInput by remember { mutableStateOf(settings.serviceName) }
    var keyInput by remember { mutableStateOf(settings.apiKey) }
    var baseInput by remember { mutableStateOf(settings.baseUrl) }
    var pathInput by remember { mutableStateOf(settings.apiPath) }
    var modelInput by remember { mutableStateOf(settings.model) }

    // 有改动才让保存按钮亮起来，免得用户以为点了没反应
    val dirty = nameInput != settings.serviceName || keyInput != settings.apiKey ||
        baseInput != settings.baseUrl || pathInput != settings.apiPath ||
        modelInput != settings.model

    fun save() {
        settings.updateServiceName(nameInput)
        settings.updateApiKey(keyInput)
        settings.updateBaseUrl(baseInput)
        settings.updateApiPath(pathInput)
        settings.updateModel(modelInput)
        // 读回来，把规范化之后的值回填到输入框
        nameInput = settings.serviceName
        keyInput = settings.apiKey
        baseInput = settings.baseUrl
        pathInput = settings.apiPath
        modelInput = settings.model
        savedHint = "已保存"
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("API 接口配置") },
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
            SectionLabel("名称")
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))

            // 三行说明，第一行讲默认怎么用，后两行讲换服务要改什么
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "默认使用 DeepSeek，仅需填写 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "改用其它服务时，以下四项配置均需修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "详细请参考服务提供方技术文档",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("API Key")
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it; result = null },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("sk-…") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (visible) "隐藏" else "显示"
                        )
                    }
                },
                supportingText = { Text("仅保存在本机，不上传任何服务器") }
            )

            Spacer(Modifier.height(8.dp))
            SectionLabel("Base URL")
            OutlinedTextField(
                value = baseInput,
                onValueChange = { baseInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("接口路径")
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("模型")
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        save()
                        scope.launch { com.oracole.readit.util.Notify.show("已保存") }
                    },
                    enabled = dirty,
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }
                FilledTonalButton(
                    onClick = {
                        // 测试前先存下来，否则测的是输入框的值、实际用的是旧配置
                        save()
                        testing = true
                        result = null
                        val t0 = System.currentTimeMillis()
                        scope.launch {
                            val r = DeepSeekClient.testKey(settings.apiKey)
                            elapsed = System.currentTimeMillis() - t0
                            result = r
                            testing = false
                        }
                    },
                    enabled = keyInput.isNotBlank() && !testing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("测试中")
                    } else Text("测试连接")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    settings.resetToDefaults()
                    nameInput = settings.serviceName
                    baseInput = settings.baseUrl
                    pathInput = settings.apiPath
                    modelInput = settings.model
                    savedHint = "已恢复默认"
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !settings.isDefault
            ) { Text("重置为预设") }

            Spacer(Modifier.height(6.dp))
            Text(
                "恢复 DeepSeek 的地址与模型，保留 API Key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            savedHint?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            result?.let { r ->
                Spacer(Modifier.height(20.dp))
                ResultCard(r, elapsed)
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ResultCard(r: AiResult, elapsed: Long) {
    val (title, detail, isError) = when (r) {
        is AiResult.Ok -> Triple(
            "连接正常",
            "延迟 ${elapsed} 毫秒 · 输入 ${r.usage.promptTokens} / 输出 ${r.usage.completionTokens} tokens",
            false
        )
        is AiResult.Fail -> when (r.kind) {
            ErrorKind.NO_KEY -> Triple("还没有填写密钥", "粘贴控制台生成的密钥", true)
            ErrorKind.INVALID_KEY -> Triple("密钥无效", "请检查是否复制完整", true)
            ErrorKind.INSUFFICIENT_BALANCE -> Triple("密钥有效，但账户余额不足", "去控制台充值", true)
            ErrorKind.RATE_LIMIT -> Triple("请求太频繁", "等几秒再试", true)
            ErrorKind.NETWORK -> Triple("网络没连上", "检查网络后重试", true)
            ErrorKind.TIMEOUT -> Triple("服务器没响应", "稍后重试", true)
            ErrorKind.EMPTY_CONTENT -> Triple("返回内容为空", "再试一次", true)
            ErrorKind.TRUNCATED -> Triple("返回被截断", "稍后重试", true)
            ErrorKind.PARSE_ERROR -> Triple("返回格式异常", "稍后重试", true)
            ErrorKind.UNKNOWN -> Triple("连接失败", r.detail, true)
        }
    }
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
