package com.oracole.readit.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.oracole.readit.data.TransitionStyle

/**
 * 页面转场的四种候选，集中在这里方便对比和删除
 *
 * 时长统一 300ms、缓动统一 FastOutSlowInEasing，这两个值是 Material 规范里
 * 页面转场的标准值，不自己调。风格之间只差位移多少、要不要缩放
 *
 * forward 表示方向：true 是进入新页面，false 是返回
 */

private const val DURATION = 300

private fun<T> spec() = tween<T>(DURATION, easing = FastOutSlowInEasing)

/** 进入时，新页面怎么做 */
fun enterFor(style: TransitionStyle, forward: Boolean): EnterTransition {
    val dir = if (forward) 1 else -1
    return when (style) {
        TransitionStyle.NONE -> EnterTransition.None
        TransitionStyle.FADE -> fadeIn(spec())
        TransitionStyle.SLIDE -> slideInHorizontally(spec()) { w -> dir * w }
        // 缩放：进入时整屏滑入；返回时上一页从左侧一半处滑回
        // 同时从 0.9 放大回原尺寸，和它当初被缩到的状态对齐
        TransitionStyle.SLIDE_SCALE -> slideInHorizontally(spec()) { w ->
            if (forward) w else -w / 2
        }
        // 视差：进入时整屏滑入（要盖住旧页）
        // 返回时上一页只需要从左侧三分之一处滑回来，
        // 它当初就是被推到这个位置的，从这里出来才是连贯的
        TransitionStyle.PARALLAX -> slideInHorizontally(spec()) { w ->
            if (forward) w else -w / 3
        }
    }
}

/** 退出时，旧页面怎么做 */
fun exitFor(style: TransitionStyle, forward: Boolean): ExitTransition {
    val dir = if (forward) 1 else -1
    return when (style) {
        TransitionStyle.NONE -> ExitTransition.None
        TransitionStyle.FADE -> fadeOut(spec())
        // 整屏滑走：新旧两页像两块板子一起平移
        TransitionStyle.SLIDE -> slideOutHorizontally(spec()) { w -> -dir * w }
        // 退一半 + 缩小 + 淡出：旧页往后退，新页从前面盖上来
        // 进入时旧页退一半并缩到 0.9，被新页盖住
        // 返回时当前页整屏滑出去，和视差同理，右边没有东西接就不能只退一半
        // 不淡出：淡化会让页面像化开，缩放已经足够表达层级，再加透明反而糊
        TransitionStyle.SLIDE_SCALE -> if (forward)
            slideOutHorizontally(spec()) { w -> -w / 2 } +
                scaleOut(spec(), targetScale = 0.9f)
        else
            slideOutHorizontally(spec()) { w -> w }
        // 视差只在进入时成立：新页整屏滑入把它盖住，所以退三分之一就够
        //
        // 返回时不一样，当前页是被推出去的，右边那三分之二没有东西来接
        // 只退三分之一就会出现停在半路然后消失。所以返回时整屏滑出去
        TransitionStyle.PARALLAX -> slideOutHorizontally(spec()) { w ->
            if (forward) -w / 3 else w
        }
    }
}
