package com.oracole.readit.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 左右两个元素：放得下排一行，放不下换行
 *
 * 为什么不能直接用 Row + weight：
 * Row 会先满足没有 weight 的那一侧，剩下的才给另一侧
 * 右侧中文一长，左侧英文就只剩几个字符宽，结果一个字母一行竖排下去
 * 给两侧都加 weight 也不行，短条目会被强行拉成两半，中间空一大截
 *
 * 所以用先量后决定：先按自然宽度量两侧，加得起来就并排，加不起来就换行
 * 这是 Compose 官方文档里的 measure-then-decide 模式
 *
 * 通用逻辑，不针对任何具体内容，任何术语 + 释义键 + 值的两段文字都适用
 */
@Composable
fun AdaptivePair(
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    SubcomposeLayout(modifier) { constraints ->
        val maxW = constraints.maxWidth
        val gapPx = gap.roundToPx()

        // ① 先按自然宽度量（不限宽），看并排需要多宽
        val leftNatural = subcompose("l1", left).first().measure(Constraints())
        val rightNatural = subcompose("r1", right).first().measure(Constraints())
        val naturalW = leftNatural.width + gapPx + rightNatural.width

        if (naturalW <= maxW) {
            // ② 并排放得下：一行
            val h = maxOf(leftNatural.height, rightNatural.height)
            layout(maxW, h) {
                leftNatural.placeRelative(0, 0)
                rightNatural.placeRelative(leftNatural.width + gapPx, 0)
            }
        } else {
            // ③ 放不下：重新按可用宽度量（让它们各自折行），右侧排到下面
            val leftFull = subcompose("l2", left)
                .first().measure(Constraints(maxWidth = maxW))
            val rightFull = subcompose("r2", right)
                .first().measure(Constraints(maxWidth = maxW))
            layout(maxW, leftFull.height + rightFull.height) {
                leftFull.placeRelative(0, 0)
                rightFull.placeRelative(0, leftFull.height)
            }
        }
    }
}
