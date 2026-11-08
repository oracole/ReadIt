package com.oracole.readit.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主色色块：一个圆盘，分四块
 *
 * 从左半圆顺时针：左半是一级容器色，右上、右下是次级和强调容器色
 * 中心圆点是主色。这样一眼能看出这套配色大概什么色调
 * 比单画一个色点信息多，比铺一整屏预览省地方
 *
 * 和设计稿 docs/mock/s_row_dark.png 一致
 */
@Composable
fun ColorDisc(
    swatch: ThemePalette.Swatch,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    /** 跟随壁纸的那一项用虚线外圈表示不是固定颜色 */
    dashedRing: Boolean = false,
    /** 选中时在主色圆点里画一个对勾 */
    checked: Boolean = false,
    ringColor: Color = Color.Transparent
) {
    val stroke = remember(size) { (size.value * 0.028f).dp }

    Canvas(modifier.size(size)) {
        val d = this.size.minDimension
        val c = Offset(d / 2f, d / 2f)
        val r = d * 0.44f
        val box = Offset(c.x - r, c.y - r)
        val sz = Size(r * 2f, r * 2f)

        val left = Color(swatch.left)
        val topRight = Color(swatch.topRight)
        val bottomRight = Color(swatch.bottomRight)
        val center = Color(swatch.center)

        // 左半圆
        drawArc(color = left, startAngle = 90f, sweepAngle = 180f, useCenter = true,
            topLeft = box, size = sz)
        // 右上四分之一
        drawArc(color = topRight, startAngle = 270f, sweepAngle = 90f, useCenter = true,
            topLeft = box, size = sz)
        // 右下四分之一
        drawArc(color = bottomRight, startAngle = 0f, sweepAngle = 90f, useCenter = true,
            topLeft = box, size = sz)

        // 中心主色圆点
        val cr = d * 0.150f
        drawCircle(center, radius = cr, center = c)

        // 外圈
        when {
            dashedRing -> {
                val path = PathEffect.dashPathEffect(
                    floatArrayOf(d * 0.030f, d * 0.026f), 0f
                )
                drawCircle(
                    color = Color(0xFFBDBDBD),
                    radius = r + stroke.toPx() * 0.35f,
                    center = c,
                    style = Stroke(width = stroke.toPx() * 0.7f, pathEffect = path)
                )
            }
            checked -> {
                drawCircle(
                    color = Color(swatch.center),
                    radius = r + stroke.toPx() * 0.5f,
                    center = c,
                    style = Stroke(width = stroke.toPx())
                )
                // 主色圆点里画对勾
                val k = cr * 0.62f
                val w = d * 0.020f
                val p0 = Offset(c.x - k * 0.75f, c.y + k * 0.05f)
                val p1 = Offset(c.x - k * 0.15f, c.y + k * 0.65f)
                val p2 = Offset(c.x + k * 0.85f, c.y - k * 0.60f)
                drawLine(Color.White, p0, p1, strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Color.White, p1, p2, strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            ringColor != Color.Transparent -> {
                drawCircle(ringColor, radius = r, center = c, style = Stroke(width = stroke.toPx() * 0.5f))
            }
        }
    }
}
