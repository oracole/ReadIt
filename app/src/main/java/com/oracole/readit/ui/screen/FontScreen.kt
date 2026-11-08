package com.oracole.readit.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.data.FontRepository
import com.oracole.readit.ui.theme.UiFont
import com.oracole.readit.ui.theme.UiFonts
import com.oracole.readit.util.Notify
import kotlinx.coroutines.launch

/**
 * 字体
 *
 * 界面字体和阅读字体分别设置，共用一个列表
 * 默认界面跟随系统、阅读用宋体，也就是这个功能出现之前的样子
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontScreen(
    uiFont: UiFont,
    readingFont: UiFont,
    onPickUi: (UiFont) -> Unit,
    onPickReading: (UiFont) -> Unit,
    onFontsChanged: (List<UiFont.Custom>) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { FontRepository(ctx) }
    val scope = rememberCoroutineScope()

    // 0 界面 1 阅读
    var tab by remember { mutableIntStateOf(0) }
    var customs by remember {
        mutableStateOf(repo.list().map { UiFont.Custom(it.fileName, it.displayName) })
    }
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<UiFont.Custom?>(null) }

    val current = if (tab == 0) uiFont else readingFont
    val onPick: (UiFont) -> Unit = if (tab == 0) onPickUi else onPickReading

    // 预览只反映当前 tab 选中的那个字体
    val family: FontFamily? = remember(current, customs) {
        if (current is UiFont.System) null else UiFonts.familyOf(ctx, current)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val r = repo.import(uri)
            importing = false
            r.fold(
                onSuccess = { f ->
                    UiFonts.invalidate()
                    customs = repo.list().map { UiFont.Custom(it.fileName, it.displayName) }
                    onFontsChanged(customs)
                    // 导完直接用到当前这一档上
                    val picked = UiFont.Custom(f.fileName, f.displayName)
                    if (tab == 0) onPickUi(picked) else onPickReading(picked)
                    Notify.show("已导入 " + f.displayName)
                },
                onFailure = { Notify.show(it.message ?: "导入失败") }
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text("字体") },
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Material 自带的分段控件，选中指示器会滑过去
            // 之前自己拿 Box 拼过一个，只有颜色渐变，看着生硬
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("界面字体", "阅读字体").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2)
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(16.dp))

            PreviewCard(family)
            Spacer(Modifier.height(20.dp))

            UiFont.builtIn.forEach { f ->
                FontRow(
                    font = f,
                    selected = current == f,
                    onClick = { onPick(f) }
                )
                Spacer(Modifier.height(10.dp))
            }

            customs.forEach { f ->
                FontRow(
                    font = f,
                    selected = current == f,
                    onClick = { onPick(f) },
                    onDelete = { pendingDelete = f }
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !importing) {
                        picker.launch(arrayOf(
                            "font/ttf", "font/otf", "application/x-font-ttf",
                            "application/x-font-opentype", "*/*"
                        ))
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (importing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("导入中", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "导入字体",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "支持 .ttf 和 .otf 文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除字体") },
            text = { Text("删除后这个字体文件也会一起删掉，确定删除「" + target.displayName + "」？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.remove(target.fileName)
                        UiFonts.invalidate()
                        customs = repo.list().map { UiFont.Custom(it.fileName, it.displayName) }
                        onFontsChanged(customs)
                        if (uiFont == target) onPickUi(UiFont.System)
                        if (readingFont == target) onPickReading(UiFont.Serif)
                        pendingDelete = null
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 预览卡
 *
 * 显示当前 tab 选中字体的实际样子
 * 第一行放大，因为骨、关、直、令在日文和简体里差别只有几个像素
 */
@Composable
private fun PreviewCard(family: FontFamily?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "关令直骨",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 44.sp,
                    lineHeight = 58.sp,
                    fontFamily = family
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))

            listOf(
                "The quick brown fox" to 16.sp,
                "jumps over the lazy dog" to 16.sp,
                "永和九年，岁在癸丑，暮春之初" to 17.sp,
                "會於會稽山陰之蘭亭，脩禊事也" to 16.sp
            ).forEachIndexed { i, (text, size) ->
                if (i == 2) Spacer(Modifier.height(8.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = size,
                        lineHeight = size * 1.6f,
                        fontFamily = family
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 一行选项
 *
 * 涟漪要裁到圆角里，否则会溢出成方块
 * 选中态的背景和边框用 animateColorAsState 过渡，直接切颜色会硬跳
 */
@Composable
private fun FontRow(
    font: UiFont,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme

    val bg by animateColorAsState(
        targetValue = if (selected) colors.secondaryContainer else colors.surface,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "fontRowBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outlineVariant,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "fontRowBorder"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "fontRowBorderWidth"
    )

    Row(
        Modifier
            .fillMaxWidth()
            // clip 必须在 clickable 之前。放后面的话涟漪按矩形算，圆角处会露出方块
            .clip(shape)
            .background(bg)
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(font.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                font.desc,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                    tint = colors.error
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = colors.primary
            )
        }
    }
}
