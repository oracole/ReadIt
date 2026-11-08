package com.oracole.readit.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 左列定宽起步、右列跟在后面，但左列宽度会跟着内容长
 *
 * 和 [AdaptivePair] 的区别：
 * AdaptivePair 是放不下就把右边整个挪到下一行
 * 适合长度差异极大的两段文字（搭配的术语和释义）
 * AlignedPair 是左列变宽把右列往右顶，右列空间不够才折行
 * 适合首列本来就该对齐的表格型内容（词根词缀 + 释义）
 *
 * 行为：
 *   1. 左列自然宽度不足 minStartWidth → 按 minStartWidth 对齐，各行左边线一致
 *   2. 左列自然宽度更长 → 左列变宽，右列被顶开
 *   3. 左列宽度到 maxStartRatio 封顶 → 左列自己折行，不再挤压右列
 */
@Composable
fun AlignedPair(
    modifier: Modifier = Modifier,
    minStartWidth: Dp = 80.dp,
    maxStartRatio: Float = 0.45f,
    gap: Dp = 12.dp,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit
) {
    SubcomposeLayout(modifier) { constraints ->
        val maxW = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val minPx = minStartWidth.roundToPx().coerceAtMost(maxW / 2)
        // 屏幕很窄时 cap 可能比 min 还小，兜一下
        val capPx = (maxW * maxStartRatio).toInt().coerceAtLeast(minPx)

        // ① 先按自然宽度量左列
        val natural = subcompose("s", start).first().measure(Constraints())
        val colW = natural.width.coerceIn(minPx, capPx)

        // ② 超过上限才需要按封顶宽度重量（让它折行）；
        //    其余情况用第一次的结果，少组合一次
        val startP = if (natural.width <= colW) natural
        else subcompose("s2", start).first().measure(Constraints(maxWidth = colW))

        // ③ 右列吃掉剩下的宽度
        val endMax = (maxW - colW - gapPx).coerceAtLeast(1)
        val endP = subcompose("e", end).first().measure(Constraints(maxWidth = endMax))

        layout(maxW, maxOf(startP.height, endP.height)) {
            startP.placeRelative(0, 0)
            endP.placeRelative(colW + gapPx, 0)
        }
    }
}
