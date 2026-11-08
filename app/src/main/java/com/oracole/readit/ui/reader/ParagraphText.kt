package com.oracole.readit.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.oracole.readit.ui.reader.Tokenizer.Word
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 一个段落：单击查词 / 长按进入选择模式
 *
 * 手势状态机（关键：必须区分"抬起"和"被滑动取消"）：
 *   按下 → 在长按超时内 →
 *     · 抬起           → 点按，取按下的那个词交给 onWordClick
 *     · 移动超过阈值   → 判定为滚动，直接放弃，不做任何事
 *     · 超时           → 长按，取按下的那个词交给 onLongPressWord
 *
 * 没有双击手势：注册 onDoubleTap 会让单击也必须等双击超时
 * 而单击查词是最高频动作，不值得为隐性手势付这个延迟
 * 也没有长按后拖动扩选：扩选在顶部操作条里做
 */
@Composable
fun ParagraphText(
    text: String,
    style: TextStyle,
    color: Color,
    letterSpacing: TextUnit,
    highlightColor: Color,
    underlineColor: Color,
    highlightRanges: List<Pair<Int, Int>>,
    /** 已收藏词的浅色底：只做标记，不参与点击判定 */
    softRanges: List<Pair<Int, Int>> = emptyList(),
    softColor: Color = Color.Unspecified,
    /** 目标词：用下划线标注，和「已收藏」的底色区分开 */
    targetRanges: List<Pair<Int, Int>> = emptyList(),
    /** 目标词下划线的颜色，调用方传正文色的淡色 */
    targetColor: Color = Color.Unspecified,
    /** 需要闪两下的区间（从词汇详情跳回来时用） */
    flashRanges: List<Pair<Int, Int>> = emptyList(),
    flashColor: Color = Color.Unspecified,
    baseOffset: Int,
    onWordClick: (word: String, start: Int, end: Int, sentence: String, context: String) -> Unit,
    onLongPressWord: (word: String, start: Int, end: Int, sentence: String, context: String) -> Unit
) {
    val words = remember(text) { Tokenizer.tokens(text) }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }

    // 未经合并的原始选区。alpha 必须挂在它上面，
    // 合并会让相邻两词变成一个新区间，key 一变，旧词就会被判为消失而闪一下
    val rawSels: List<Pair<Int, Int>> = remember(highlightRanges, baseOffset, text) {
        highlightRanges.mapNotNull { (gs, ge) ->
            val s = (gs - baseOffset).coerceIn(0, text.length)
            val e = (ge - baseOffset).coerceIn(0, text.length)
            if (e > s) s to e else null
        }
    }

    val localSoft: List<Pair<Int, Int>> = remember(softRanges, baseOffset, text) {
        if (softRanges.isEmpty()) emptyList()
        else softRanges.mapNotNull { (gs, ge) ->
            val s = (gs - baseOffset).coerceIn(0, text.length)
            val e = (ge - baseOffset).coerceIn(0, text.length)
            if (e > s) s to e else null
        }
    }

    val localTarget: List<Pair<Int, Int>> = remember(targetRanges, baseOffset, text) {
        if (targetRanges.isEmpty()) emptyList()
        else targetRanges.mapNotNull { (gs, ge) ->
            val s = (gs - baseOffset).coerceIn(0, text.length)
            val e = (ge - baseOffset).coerceIn(0, text.length)
            if (e > s) s to e else null
        }
    }
    val targetRects: List<Rect> = remember(layout, localTarget) {
        val lr = layout
        if (lr == null) emptyList() else localTarget.flatMap { buildRects(lr, it.first, it.second) }
    }

    val localFlash: List<Pair<Int, Int>> = remember(flashRanges, baseOffset, text) {
        if (flashRanges.isEmpty()) emptyList()
        else flashRanges.mapNotNull { (gs, ge) ->
            val s = (gs - baseOffset).coerceIn(0, text.length)
            val e = (ge - baseOffset).coerceIn(0, text.length)
            if (e > s) s to e else null
        }
    }
    val flashRects: List<Rect> = remember(layout, localFlash) {
        val lr = layout
        if (lr == null) emptyList() else localFlash.flatMap { buildRects(lr, it.first, it.second) }
    }
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(localFlash) {
        if (localFlash.isEmpty()) {
            flashAlpha.snapTo(0f)
        } else {
            repeat(2) {
                flashAlpha.animateTo(1f, tween(280))
                flashAlpha.animateTo(0f, tween(280))
            }
        }
    }

    // 相邻选区先合并成组：两词之间只有空白 → 同一个组 → 画一个框
    // 判据是文本内容而不是像素距离，所以字号 14–26sp 都成立
    // 注意：只在同一行内连成一块。跨行折行时 buildRects 仍会返回每行一个矩形
    // 各画各的圆角（用户明确否掉了跨行整条缎带的方案）
    val groups: List<Pair<Int, Int>> = remember(rawSels, text) {
        mergeAdjacent(rawSels, text)
    }

    // 一个框一份动画状态。列表本身是可观察的，增删会触发重绘
    val boxes = remember { mutableStateListOf<BoxState>() }

    LaunchedEffect(groups, layout) {
        val lr = layout ?: return@LaunchedEffect
        val targets = groups.map { g -> g to buildRects(lr, g.first, g.second) }

        // 先把旧框的区间拍下来：下面会改动 sel，匹配时要看改动前的它
        val oldBoxes = boxes.toList()
        val oldSel = oldBoxes.associateWith { it.sel }

        val reused = HashSet<BoxState>()
        val keep = ArrayList<BoxState>(targets.size)
        val pending = ArrayList<Pair<Pair<Int, Int>, List<Rect>>>()

        // 一个旧框被几个目标同时覆盖：>1 说明发生了拆分
        // 拆分时两侧的形状都不做插值，否则缩小中的那个框会一路盖住已经被拆出去的词
        // 半透明叠半透明就是一道更深的色带
        val hits = oldBoxes.associateWith { b ->
            targets.count { overlap(oldSel.getValue(b), it.first) }
        }

        // ① 一对一重叠匹配：区间有交集就是同一个框 → alpha 原样保留（不闪）
        // 只有形状从旧位置滑到新位置（"长"出来的观感）
        for ((g, rects) in targets) {
            val hit = oldBoxes.firstOrNull { it !in reused && overlap(oldSel.getValue(it), g) }
            if (hit == null) {
                pending += g to rects
                continue
            }
            reused += hit
            val animate = hits.getValue(hit) <= 1
            if (hit.moveTo(g, rects, animate)) launch { hit.grow.animateTo(1f, tween(150)) }
            keep += hit
        }

        // ② 剩下的目标：要么是从旧框里拆出来的，要么是全新选中的词
        for ((g, rects) in pending) {
            val parent = oldBoxes.firstOrNull { overlap(oldSel.getValue(it), g) }
            if (parent != null) {
                // 拆分（取消中间一个词 → 一个框裂成两个）：继承满 alpha，形状直接落位
                // 不做形变插值，两个子框会在中间长时间互相覆盖
                // 半透明叠半透明会出现一道更深的色带
                val b = BoxState(g, 1f, rects)
                boxes += b
                keep += b
            } else {
                // 全新选中的词：自己淡入，已有的框不受影响
                val b = BoxState(g, 0f, rects)
                boxes += b
                keep += b
                launch { b.alpha.animateTo(1f, tween(140)) }
            }
        }

        // ③ 没被认领的旧框
        val kept = keep.map { it.to }
        for (b in oldBoxes.filterNot { it in keep }) {
            if (b.to.all { r -> kept.any { k -> k.any { kr -> contains(kr, r) } } }) {
                // 它的地盘已经被别的框整个盖住了（比如加选相邻词后并成了一个大框
                // 或者全句把已选的词吸进去了）。这时再淡出会在同一处叠出深色
                // 直接撤掉，视觉上什么都不会少
                boxes.remove(b)
            } else {
                launch {
                    b.alpha.animateTo(0f, tween(160))
                    boxes.remove(b)
                }
            }
        }

        // ④ 收尾：任何留存下来的框都必须回到 alpha = 1
        // 上一次淡入的协程会随着本次 effect 重启被取消，光靠它可能停在半透明上
        for (b in keep) {
            if (b.alpha.value < 1f) launch { b.alpha.animateTo(1f, tween(140)) }
        }
    }

    val softRects: List<Rect> = remember(layout, localSoft) {
        val lr = layout
        if (lr == null) emptyList() else localSoft.flatMap { buildRects(lr, it.first, it.second) }
    }

    val density = LocalDensity.current
    val padH = with(density) { 2.dp.toPx() }
    val padV = with(density) { 1.5.dp.toPx() }

    val radius = with(density) { 5.dp.toPx() }
    val underlineWidth = with(density) { 1.5.dp.toPx() }
    val underlineGap = with(density) { 2.dp.toPx() }


    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // 已收藏词的浅色底，画在高亮之下
                if (softRects.isNotEmpty() && softColor != Color.Unspecified) {
                    softRects.forEach { r ->
                        drawRoundRect(
                            color = softColor,
                            topLeft = Offset(r.left - padH, r.top + padV),
                            size = Size(r.width + padH * 2, r.height - padV * 2),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                }
                // 目标词（生成文章时指定的词）：淡色下划线，不用底色
                // 底色会压在文字上影响辨认（LingQ / Readlang 用户反馈一致）
                // 而下划线在阅读态是唯一出现的线，不会和点击高亮冲突
                // ，点击才有线 + 框，两者一眼可分
                if (targetRects.isNotEmpty() && targetColor != Color.Unspecified) {
                    targetRects.forEach { r ->
                        drawLine(
                            color = targetColor,
                            start = Offset(r.left, r.bottom - padV - underlineGap),
                            end = Offset(r.right, r.bottom - padV - underlineGap),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }
                // 闪烁：两次亮起再熄灭，提示我带你到这里了
                if (flashRects.isNotEmpty() && flashAlpha.value > 0.01f &&
                    flashColor != Color.Unspecified
                ) {
                    flashRects.forEach { r ->
                        drawRoundRect(
                            color = flashColor.copy(alpha = flashColor.alpha * flashAlpha.value),
                            topLeft = Offset(r.left - padH, r.top + padV),
                            size = Size(r.width + padH * 2, r.height - padV * 2),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
                }
                // 选中高亮：一个组一个框。相邻词已经被 mergeAdjacent 并成同一个区间
                // 所以中间的空格天然落在矩形内部，没有拼接、没有接缝、也没有补丁块
                // 动画值在这里（绘制阶段）读，不在 composition 里读：
                // 动画只触发重绘，不会重组整段 Text
                boxes.forEach { box ->
                    val a = box.alpha.value
                    if (a <= 0.01f) return@forEach
                    box.rects().forEach { r ->
                        drawRoundRect(
                            color = highlightColor.copy(alpha = highlightColor.alpha * a),
                            topLeft = Offset(r.left - padH, r.top + padV),
                            size = Size(r.width + padH * 2, r.height - padV * 2),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                        // 点击/选中的下划线：粗一点、深一点，跟随各自 alpha 淡入淡出
                        // 目标词也用下划线，但更细更淡；区分靠框，有框才是选中
                        // 合并成一个区间后，下划线自然连成一条，读起来是一个整体
                        drawLine(
                            color = underlineColor.copy(alpha = underlineColor.alpha * a),
                            start = Offset(r.left, r.bottom - padV - underlineGap),
                            end = Offset(r.right, r.bottom - padV - underlineGap),
                            strokeWidth = underlineWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            .pointerInput(text, baseOffset) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val slop = viewConfiguration.touchSlop
                    val timeout = viewConfiguration.longPressTimeoutMillis

                    // 判定：抬起 / 滚动 / 长按
                    val verdict = withTimeoutOrNull(timeout) {
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id }
                                ?: return@withTimeoutOrNull "cancel"
                            if (!ch.pressed) return@withTimeoutOrNull "up"
                            if ((ch.position - downPos).getDistance() > slop)
                                return@withTimeoutOrNull "scroll"
                        }
                        @Suppress("UNREACHABLE_CODE") "cancel"
                    }

                    if (verdict != "up") {
                        // 只有"超时"才当作长按；滚动/取消一律忽略
                        if (verdict != null) return@awaitEachGesture
                        val lr = layout ?: return@awaitEachGesture
                        val w = wordAtPoint(lr, words, downPos) ?: return@awaitEachGesture
                        val (snt1, ctx1) = Tokenizer.sentenceAndContext(text, w.start, w.end)
                        onLongPressWord(
                            w.text, baseOffset + w.start, baseOffset + w.end, snt1, ctx1
                        )
                        return@awaitEachGesture
                    }

                    val lr = layout ?: return@awaitEachGesture
                    val w = wordAtPoint(lr, words, downPos) ?: return@awaitEachGesture
                    val (snt2, ctx2) = Tokenizer.sentenceAndContext(text, w.start, w.end)
                    onWordClick(
                        w.text, baseOffset + w.start, baseOffset + w.end, snt2, ctx2
                    )
                }
            },
        style = style.copy(color = color, letterSpacing = letterSpacing),
        onTextLayout = { layout = it }
    )
}

/**
 * 一个框的动画状态
 *
 * 核心原则，身份和几何分开，动画只挂在身份上
 * alpha 只在这个框出现或消失时动，加选相邻词时区间变大但仍然是同一个框，
 * 所以 alpha 保持 1，不会重播淡入，这就是已选中的词不闪
 *
 * 形状变化用 grow 单独做插值（0 到 1），它只影响绘制，不影响任何 effect 的 key
 */
private class BoxState(
    sel: Pair<Int, Int>,
    alpha: Float,
    rects: List<Rect>
) {
    /** 这个框代表哪个区间（段内偏移）。变了说明是同一个框在长大 / 缩小 */
    var sel by mutableStateOf(sel)

    /** 淡入淡出。只在出现 / 消失时动 */
    val alpha = Animatable(alpha)

    /** 形状插值的起点 */
    var from by mutableStateOf(rects)

    /** 形状插值的终点，也就是静止时该有的样子 */
    var to by mutableStateOf(rects)

    /** 0→1 的形变进度 */
    val grow = Animatable(1f)

    /**
     * 挪到新区间。[rects] 是静止时应有的形状。[animateShape] 为 false 时直接落位
     * 返回是否需要播形变动画
     *
     * 护栏：只有矩形数量相同、且每行 top 相同才插值
     * 跨行折行一律直接跳过去，宁可跳，不可错
     */
    suspend fun moveTo(g: Pair<Int, Int>, rects: List<Rect>, animateShape: Boolean): Boolean {
        val same = g == sel
        from = to
        sel = g
        to = rects
        val lerpable = animateShape && !same &&
            from.size == to.size &&
            from.indices.all { abs(from[it].top - to[it].top) < 0.5f }
        if (!lerpable) {
            from = rects
            grow.snapTo(1f)
            return false
        }
        grow.snapTo(0f)
        return true
    }

    /** 此刻该画成什么样：形变进行中就在 from / to 之间插值（只动左右两边） */
    fun rects(): List<Rect> {
        val p = grow.value
        if (p >= 1f || from.size != to.size) return to
        if (from.indices.any { abs(from[it].top - to[it].top) > 0.5f }) return to
        return to.indices.map { i ->
            val f = from[i]
            val t = to[i]
            Rect(
                left = f.left + (t.left - f.left) * p,
                top = t.top,
                right = f.right + (t.right - f.right) * p,
                bottom = t.bottom
            )
        }
    }
}

/** 两个区间有交集（含端点相接） */
private fun overlap(a: Pair<Int, Int>, b: Pair<Int, Int>) =
    a.first <= b.second && b.first <= a.second

/** [inner] 是否被 [outer] 完全盖住（各边留 0.5px 容差） */
private fun contains(outer: Rect, inner: Rect) =
    inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f &&
        inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f

/**
 * 合并相邻的选区
 *
 * 规则：两个区间之间只有空白 → 并成一个整体；夹了标点或别的词 → 保持分开
 *
 * 用文本内容判断而不是像素距离：字号 14–26sp 变化时空格宽度跟着变
 * 固定像素阈值必然在某些字号下失效
 */
private fun mergeAdjacent(
    ranges: List<Pair<Int, Int>>,
    src: String
): List<Pair<Int, Int>> {
    if (ranges.size < 2) return ranges
    val sorted = ranges.sortedBy { it.first }
    val out = ArrayList<Pair<Int, Int>>(sorted.size)
    var curS = sorted[0].first
    var curE = sorted[0].second
    for (i in 1 until sorted.size) {
        val s = sorted[i].first
        val e = sorted[i].second
        if (s <= curE) {
            if (e > curE) curE = e
            continue
        }
        val gap = src.substring(curE.coerceIn(0, src.length), s.coerceIn(0, src.length))
        if (gap.all { it.isWhitespace() }) {
            curE = e
        } else {
            out.add(curS to curE)
            curS = s; curE = e
        }
    }
    out.add(curS to curE)
    return out
}

private fun wordAtPoint(lr: TextLayoutResult, words: List<Word>, pos: Offset): Word? {
    val offset = runCatching { lr.getOffsetForPosition(pos) }.getOrNull() ?: return null
    if (offset < 0) return null
    val w = Tokenizer.wordAt(words, offset)
        ?: Tokenizer.wordAt(words, (offset - 1).coerceAtLeast(0)) ?: return null
    val slack = lr.size.height * 0.08f
    val line = runCatching { lr.getLineForVerticalPosition(pos.y) }.getOrNull() ?: return null
    if (pos.y < lr.getLineTop(line) - slack || pos.y > lr.getLineBottom(line) + slack) return null
    val first = runCatching { lr.getBoundingBox(w.start) }.getOrNull() ?: return null
    val last = runCatching { lr.getBoundingBox(w.end - 1) }.getOrNull() ?: return null
    // 严格：必须落在首字符左边到末字符右边之间（不留额外余量，避免"点空白选中"）
    if (pos.x < first.left || pos.x > last.right) return null
    return w
}

/** 把 [start, end) 按行切矩形；同一行内的连续片段合并成一块 */
private fun buildRects(lr: TextLayoutResult, start: Int, end: Int): List<Rect> {
    if (end <= start) return emptyList()
    val raw = ArrayList<Rect>(4)
    var line = lr.getLineForOffset(start)
    val lastLine = lr.getLineForOffset(end - 1)
    while (line <= lastLine) {
        val s = max(start, lr.getLineStart(line))
        val e = min(end, lr.getLineEnd(line, visibleEnd = true))
        if (e > s) {
            val firstBox = runCatching { lr.getBoundingBox(s) }.getOrNull()
            val lastBox = runCatching { lr.getBoundingBox(e - 1) }.getOrNull()
            val left = firstBox?.left ?: lr.getHorizontalPosition(s, true)
            val right = lastBox?.right ?: lr.getLineRight(line)
            if (right > left) raw.add(Rect(left, lr.getLineTop(line), right, lr.getLineBottom(line)))
        }
        line++
    }
    return raw
}
