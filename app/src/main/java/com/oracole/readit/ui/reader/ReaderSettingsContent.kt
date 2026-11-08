package com.oracole.readit.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 阅读设置的内容，阅读页底部弹层和设置页共用 */
@Composable
fun ReaderSettingsContent(
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    // 在阅读页弹层里用阅读主题色；在设置页里用应用主题色，否则深色页面配浅色文字会看不清
    useReaderColors: Boolean = true
) {
    val t = settings.resolvedTheme()
    val labelColor = if (useReaderColors) t.text else MaterialTheme.colorScheme.onSurface
    val sliderColors = if (useReaderColors) SliderDefaults.colors(
        thumbColor = t.text, activeTrackColor = t.text.copy(alpha = 0.7f),
        inactiveTrackColor = t.text.copy(alpha = 0.2f)
    ) else SliderDefaults.colors()
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text("字号", style = MaterialTheme.typography.labelLarge, color = labelColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", fontSize = 14.sp, color = labelColor)
            Slider(
                value = settings.fontSize,
                onValueChange = { settings.updateFontSize(it) },
                valueRange = 14f..26f, steps = 11,
                colors = sliderColors,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            Text("A", fontSize = 22.sp, color = labelColor)
        }

        Spacer(Modifier.height(8.dp))
        Text("行距", style = MaterialTheme.typography.labelLarge, color = labelColor)
        Slider(
            value = settings.lineHeight,
            onValueChange = { settings.updateLineHeight(it) },
            valueRange = 1.3f..2.2f, steps = 8, colors = sliderColors
        )

        Spacer(Modifier.height(8.dp))
        Text("边距", style = MaterialTheme.typography.labelLarge, color = labelColor)
        Slider(
            value = settings.margin.toFloat(),
            onValueChange = { settings.updateMargin(it.toInt()) },
            valueRange = 8f..40f, steps = 15, colors = sliderColors
        )

        Spacer(Modifier.height(6.dp))
        Text("背景", style = MaterialTheme.typography.labelLarge, color = labelColor)
        Spacer(Modifier.height(10.dp))
        ThemePicker(settings, t, labelColor)
        Spacer(Modifier.height(36.dp))
    }
}

// 背景选择，内置色加自定义色（色盘或十六进制）
// 用可换行的 FlowRow 布局，避免两行格子把横向空间撑爆
// 色块描边用哪个颜色
// 不能直接用 labelColor，设置页里它跟的是应用主题
// 而格子画的是阅读背景，应用浅色、阅读深色时
// 黑描边压在深色格子上等于没有，选中了也看不出来
// 所以按格子自己的底色算，深底用白边，浅底用黑边
private fun outlineFor(bg: Color, selected: Boolean): Color {
    val base = if (bg.luminance() < 0.5f) Color.White else Color.Black
    return base.copy(alpha = if (selected) 0.75f else 0.22f)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePicker(settings: ReaderSettings, cur: ReaderTheme, labelColor: androidx.compose.ui.graphics.Color) {
    var showPicker by remember { mutableStateOf(false) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 跟随应用固定排在最前
        // 只有一项，明暗由上面的开关决定，这里只选配色来源
        val app = ReaderTheme.AppFollow
        run {
            // 用当前明暗下的实际配色画格子，选了之后一眼能对上
            val shown = settings.resolvedTheme().takeIf { it.id == app.id } ?: app
            val sel = cur.id == app.id
            Box(
                modifier = Modifier
                    .size(44.dp)
                    // 先 clip 再 clickable，涟漪才按圆角裁
                    // 反过来的话涟漪按矩形算，四个角会露出方块
                    .clip(MaterialTheme.shapes.small)
                    .background(shown.bg)
                    .border(
                        width = if (sel) 2.dp else 1.dp,
                        color = outlineFor(shown.bg, sel),
                        shape = MaterialTheme.shapes.small
                    )
                    .clickable { settings.updateTheme(app) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = app.label,
                    // AutoAwesome 的字形不是对称的：三颗星星整体偏右
                    // 直接居中会看起来往左歪。往左挪 0.5dp 让它视觉上居中
                    modifier = Modifier.size(20.dp).offset(x = (-0.5).dp),
                    tint = if (cur.id == app.id) shown.text else shown.text.copy(alpha = 0.7f)
                )
            }
        }

        // 只列当前模式下可用的配色：浅色 7 种，深色 2 种
        settings.presetsForMode.forEach { th ->
            val sel = cur.id == th.id
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(th.bg)
                    .border(
                        width = if (sel) 2.dp else 1.dp,
                        color = outlineFor(th.bg, sel),
                        shape = MaterialTheme.shapes.small
                    )
                    .clickable { settings.updateTheme(th) },
                contentAlignment = Alignment.Center
            ) {
                if (cur.id == th.id) Text("✓", color = th.text, fontSize = 18.sp)
            }
        }

        // 自定义入口
        val selCustom = cur.id == ReaderSettings.CUSTOM_ID
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (selCustom) cur.bg else labelColor.copy(alpha = 0.08f)
                )
                .border(
                    width = if (selCustom) 2.dp else 1.dp,
                    // 没选自定义时底色是半透明蒙层，没有自己的颜色可比
                    // 这时仍按 labelColor 算
                    color = if (selCustom) outlineFor(cur.bg, true)
                    else labelColor.copy(alpha = 0.18f),
                    shape = MaterialTheme.shapes.small
                )
                .clickable { showPicker = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Palette, "自定义颜色",
                Modifier.size(20.dp),
                tint = if (cur.id == ReaderSettings.CUSTOM_ID) cur.text else labelColor.copy(alpha = 0.65f))
        }
    }

    if (showPicker) {
        ColorPickerDialog(
            // 已经有自定义色就用它，否则用当前主题色作为起点
            // 原来两个分支都写 cur.bg，等于没判断
            // 切到别的主题后再打开调色盘，会以那个主题色开始，看起来像自定义色丢了
            initial = settings.savedCustomBg() ?: cur.bg,
            onDismiss = { showPicker = false },
            onPick = { settings.updateCustomBg(it); showPicker = false }
        )
    }
}

/**
 * 取色。色盘用 HSV 方块 + 色相条；下面给十六进制输入，两条路都通
 */
@Composable
private fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit
) {
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    // 打开时就把当前颜色的十六进制填进去
    // 之前初值是空串，虽然色块显示着颜色，但输入框是空的，看着像没值
    var hex by remember {
        mutableStateOf(
            String.format("#%06X", 0xFFFFFF and initial.toArgb())
        )
    }

    val current = remember(hue, sat, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    }

    // 用户在色盘上拖动时，输入框跟着变
    LaunchedEffect(current) {
        val v = String.format("#%06X", 0xFFFFFF and current.toArgb())
        if (v != hex) hex = v
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义背景") },
        text = {
            Column {
                // ---- 饱和度 / 明度 方块 ----
                BoxWithConstraints(
                    Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.small)
                ) {
                    val w = constraints.maxWidth.toFloat()
                    val h = constraints.maxHeight.toFloat()
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val p = change.position
                                sat = (p.x / w).coerceIn(0f, 1f)
                                value = 1f - (p.y / h).coerceIn(0f, 1f)
                            }
                        }.pointerInput(Unit) {
                            detectTapGestures { p ->
                                sat = (p.x / w).coerceIn(0f, 1f)
                                value = 1f - (p.y / h).coerceIn(0f, 1f)
                            }
                        }
                    ) {
                        // 白 → 纯色（横向）
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))
                            )
                        )
                        // 透明 → 黑（纵向）
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        // 当前点
                        drawCircle(
                            color = Color.White,
                            radius = 7.dp.toPx(),
                            center = Offset(sat * size.width, (1f - value) * size.height),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---- 色相条 ----
                val hueColors = (0..6).map {
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)))
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
                    val w = constraints.maxWidth.toFloat()
                    Box(
                        Modifier.fillMaxWidth().height(24.dp)
                            .align(Alignment.Center)
                            .background(
                                Brush.horizontalGradient(hueColors),
                                MaterialTheme.shapes.extraSmall
                            )
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    hue = (change.position.x / w).coerceIn(0f, 1f) * 360f
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { p ->
                                    hue = (p.x / w).coerceIn(0f, 1f) * 360f
                                }
                            }
                    )
                    // 选中位置指示器：一个白色圆环套着当前色，跨在色条上下
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = (hue / 360f) * size.width
                        val cy = size.height / 2f
                        val r = 9.dp.toPx()
                        // 外层白环（衬底，保证在任何色相上都看得见）
                        drawCircle(Color.White, radius = r, center = Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))
                        // 内层描边
                        drawCircle(
                            Color.Black.copy(alpha = 0.25f),
                            radius = r + 1.5.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.dp.toPx())
                        )
                        // 中心填当前色
                        drawCircle(current, radius = r - 2.dp.toPx(), center = Offset(cx, cy))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- 预览 + 十六进制 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp)
                            .background(current, MaterialTheme.shapes.small)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), MaterialTheme.shapes.small)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { input ->
                            hex = input.filter { it.isLetterOrDigit() || it == '#' }.take(7)
                            parseHex(hex)?.let { c ->
                                val a = FloatArray(3)
                                android.graphics.Color.colorToHSV(c.toArgb(), a)
                                hue = a[0]; sat = a[1]; value = a[2]
                            }
                        },
                        label = { Text("十六进制") },
                        placeholder = { Text("#FAF9F5") },
                        singleLine = true,
                        isError = hex.isNotEmpty() && parseHex(hex) == null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 支持 #RGB / #RRGGBB / RRGGBB */
private fun parseHex(s: String): Color? {
    val v = s.trim().removePrefix("#")
    return runCatching {
        when (v.length) {
            3 -> Color(
                android.graphics.Color.rgb(
                    v[0].digit() * 17, v[1].digit() * 17, v[2].digit() * 17
                )
            )
            6 -> Color(android.graphics.Color.parseColor("#$v"))
            else -> null
        }
    }.getOrNull()
}

private fun Char.digit(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> 0
}
