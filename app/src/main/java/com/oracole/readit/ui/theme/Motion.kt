package com.oracole.readit.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 动效参数。数值直接取自 Material 3 Expressive 的官方 token
 *
 * 来源是 androidx.compose.material3:material3:1.4.0 里的
 * ExpressiveMotionTokens，从字节码读出来的常量，不是估的：
 *
 *     default spatial   dampingRatio 0.8  stiffness  380
 *     fast    spatial   dampingRatio 0.6  stiffness  800
 *     slow    spatial   dampingRatio 0.8  stiffness  200
 *     所有 effects      dampingRatio 1.0  stiffness 1600（fast 是 3800）
 *
 * 为什么分两类：
 *
 * spatial，位置、尺寸、旋转、圆角。允许过冲，落位时弹一下
 * 这样运动看起来有重量
 * effects，颜色、透明度。绝不能过冲，否则会闪成负值或越界的颜色
 *
 * 为什么不用 MaterialTheme.motionScheme：
 * 那套 API 在 material3 1.4.0 里还是 internal，1.5.0 才稳定
 * 项目现在走 compose-bom 2024.09.00，拿不到。这里把数值抄过来
 * 拿到的是同一套物理参数，只差一个主题接口
 *
 * 用的时候按动画在动什么来选：
 *
 *     位移、尺寸、旋转  → spatial() / spatialFast()
 *     颜色、透明度      → effects() / effectsFast()
 */
object Motion {

    const val SPATIAL_DAMPING = 0.8f
    const val SPATIAL_STIFFNESS = 380f
    const val SPATIAL_FAST_DAMPING = 0.6f
    const val SPATIAL_FAST_STIFFNESS = 800f
    const val EFFECTS_DAMPING = 1.0f
    const val EFFECTS_STIFFNESS = 1600f
    const val EFFECTS_FAST_STIFFNESS = 3800f

    /** 位置、尺寸变化。默认速度，用于页面、sheet 这类占用较大面积的元素 */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = SPATIAL_DAMPING, stiffness = SPATIAL_STIFFNESS)

    /** 位置、尺寸变化。快速，用于按钮、开关这类小元素 */
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = SPATIAL_FAST_DAMPING, stiffness = SPATIAL_FAST_STIFFNESS)

    /**
     * 位置变化，但不过冲
     *
     * dampingRatio = 1.0 是临界阻尼，到位就停，不会冲过头再弹回来
     * 用在 tab 切换、筛选切换这类用户会连着点的地方：
     * 回弹单看挺活泼，连点五下就是五次晃动，很烦
     *
     * 刚度比 spatial() 略高：位移只有三分之一屏，不需要那么长的减速尾巴
     */
    fun <T> spatialNoBounce(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1.0f, stiffness = 550f)

    /** 颜色、透明度变化。不过冲 */
    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = EFFECTS_DAMPING, stiffness = EFFECTS_STIFFNESS)

    /** 颜色、透明度变化。快速 */
    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = EFFECTS_DAMPING, stiffness = EFFECTS_FAST_STIFFNESS)

    /**
     * 用于需要显式加无回弹的地方（跟随手指拖动的元素、涟漪）
     * 这不是官方 token，是 Compose 自带常量，保留一个名字方便统一替换
     */
    val noBounce = Spring.DampingRatioNoBouncy

}
