package com.oracole.readit.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oracole.readit.data.Article
import com.oracole.readit.util.Notify
import com.oracole.readit.util.Tts
import com.oracole.readit.data.DictRepository
import com.oracole.readit.data.ai.AiResult
import com.oracole.readit.data.ai.DeepSeekClient
import com.oracole.readit.data.ai.ErrorKind
import com.oracole.readit.data.ai.Prompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    article: Article,
    settings: ReaderSettings,
    /** 阅读字体。null 表示用衬线，和这个功能出现之前一样 */
    readingFamily: androidx.compose.ui.text.font.FontFamily? = null,
    dictRepo: DictRepository,
    // 已收藏的词，传State而不是值
    // 传值的话收藏任何词都会让这个函数整个重跑，1300行，长文下零点几秒
    // 传State后只有真正读value的地方才会订阅读，
    // 词卡判断星标亮不亮，正文高亮开关打开时才算
    // 收藏一个词时正在看的正文不会重画
    collectedState: androidx.compose.runtime.State<Set<String>>,
    apiKey: String,
    model: String,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    /** 最后一个参数是 AI 判定的原形，没有则为 null */
    onCollect: (word: String, phonetic: String?, translation: String, sentence: String, form: String?, base: String?) -> Unit,
    /** 第二个参数是该词在当前语境下的原形，可为空 */
    onUncollect: (word: String, base: String?) -> Unit,
    onSaveProgress: (Int) -> Unit,
    usageRepo: com.oracole.readit.data.ai.AiUsageRepository? = null,
    cacheRepo: com.oracole.readit.data.ai.AiCacheRepository? = null,
    /** 从词汇详情跳来时：要定位的句子与词 */
    anchorSentence: String? = null,
    anchorWord: String? = null,
    onToggleTargets: (Boolean) -> Unit = {},
    /** 打开「翻译全文」配置页 */
    onTranslateArticle: () -> Unit = {},
    /** 已收藏的词被再次查询时，把当前句补进出处 */
    /** 第四个参数是该词在当前语境下的原形，可为空 */
    onAddContext: (word: String, sentence: String, form: String?, base: String?) -> Unit =
        { _, _, _, _ -> },
    /** 把「当前点的形态」记到某个原形名下，用于详情页那行绿字 */
    onAddForm: (base: String, form: String) -> Unit = { _, _ -> }
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val innerToast = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // 高亮 / 卡片
    var lookup by remember { mutableStateOf<WordLookup?>(null) }
    // 整句或选词卡片，第二个值是高亮区间
    // 用List而不是单个区间，因为离散选词时要选上文几个词加上下文几个词，
    // 中间没选中的词不能被框住，整句模式传一个区间，行为不变
    var sentenceSel by remember { mutableStateOf<Pair<String, List<Pair<Int, Int>>>?>(null) }
    // 选择模式（长按进入）
    var selecting by remember { mutableStateOf(false) }
    // 选中的词，离散的，可以跳过中间词，每项是start、end、word
    // 和wholeSentence互斥
    var picked by remember { mutableStateOf<List<Triple<Int, Int, String>>>(emptyList()) }
    // 选中的整句范围，和picked互斥
    var wholeSentence by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // 单词或词组卡片对应的高亮区间
    // 用List而不是单个区间，离散选词时不能把首词到末词之间连成一片
    var wordRanges by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    // 卡片内的轻提示，Snackbar会被卡片挡住，所以放进卡片里显示
    var cardToast by remember { mutableStateOf<String?>(null) }
    // 整句翻译
    var translating by remember { mutableStateOf(false) }
    var translation by remember { mutableStateOf<String?>(null) }
    var translationNote by remember { mutableStateOf<String?>(null) }
    // AI解析，只在整句卡片里用
    var explaining by remember { mutableStateOf(false) }
    var explainResult by remember { mutableStateOf<com.oracole.readit.data.ai.ExplainResult?>(null) }

    fun exitSelection() {
        selecting = false
        picked = emptyList()
        wholeSentence = null
    }

    BackHandler(enabled = selecting) { exitSelection() }

    // 段落，由连续两个及以上换行分隔的块
    // 返回文本和在全文中的起始偏移
    //
    // 块内的单个换行怎么处理，分两种情况
    // 普通文章也就是导入的TXT，替换成空格，因为TXT常常是硬折行的，
    // 一行的结尾只是排版折断，不是作者的分段，替换后长度不变，偏移仍精确
    // 译文也就是sourceName为translated的，原样保留，句对照里
    // 一句英文一行、一句中文一行靠的就是单个换行，替换成空格会全部串成一段
    val keepLineBreaks = article.sourceName == "translated"
    val paraList = remember(article.id, keepLineBreaks) {
        val src = article.content
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < src.length) {
            while (i < src.length && src[i].isWhitespace()) i++
            if (i >= src.length) break
            val start = i
            var j = i
            var breaks = 0
            while (j < src.length) {
                if (src[j] == '\n') {
                    var k = j
                    while (k < src.length && (src[k] == '\n' || src[k] == '\r')) { k++; breaks++ }
                    if (breaks >= 2) break
                    breaks = 0
                    j = k
                } else j++
            }
            var end = j
            while (end > start && src[end - 1].isWhitespace()) end--
            if (end > start) {
                val raw = src.substring(start, end)
                out.add(
                    (if (keepLineBreaks) raw.replace('\r', ' ')
                     else raw.replace('\r', ' ').replace('\n', ' ')) to start
                )
            }
            i = j
            while (i < src.length && (src[i] == '\n' || src[i] == '\r')) i++
        }
        out
    }
    val paragraphs = remember(paraList) { paraList.map { it.first } }
    val offsets = remember(paraList) { IntArray(paraList.size) { paraList[it].second } }

    // 恢复上次位置
    var restored by remember(article.id) { mutableStateOf(false) }
    LaunchedEffect(offsets, article.id) {
        if (!restored && article.progressOffset > 0 && offsets.isNotEmpty()) {
            val idx = offsets.indexOfLast { it <= article.progressOffset }.coerceAtLeast(0)
            if (idx > 0) listState.scrollToItem(idx)
            restored = true
        }
    }

    // ---- 从词汇详情跳进来时的定位 ----
    // 找到锚点所在的段落与字符偏移
    val anchor = remember(anchorSentence, paraList) {
        val sent = anchorSentence?.trim().orEmpty()
        if (sent.isEmpty()) return@remember null
        val key = sent.take(40)
        val hit = paraList.firstOrNull { it.first.contains(key) } ?: return@remember null
        val paraStart = hit.second
        val local = hit.first.indexOf(key)
        if (local < 0) return@remember null
        val absStart = paraStart + local
        val absEnd = (absStart + sent.length).coerceAtMost(paraStart + hit.first.length)
        Triple(paraStart, absStart to absEnd, hit.first)
    }

    var flashRanges by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var returnOffset by remember { mutableStateOf<Int?>(null) }
    var jumped by remember(article.id, anchorSentence) { mutableStateOf(false) }

    LaunchedEffect(anchor) {
        val a = anchor ?: return@LaunchedEffect
        if (jumped) return@LaunchedEffect
        jumped = true
        // 记住跳走前的阅读位置，供回到阅读进度使用
        val prev = offsets.getOrNull(listState.firstVisibleItemIndex) ?: 0
        val idx = offsets.indexOfLast { it <= a.second.first }.coerceAtLeast(0)
        // 动画滚动到目标段
        listState.animateScrollToItem(idx)
        delay(220)
        flashRanges = listOf(a.second)
        returnOffset = prev
        // 闪烁结束后清掉，避免重进页面又闪
        delay(1400)
        flashRanges = emptyList()
    }

    // ---- 进度 ----
    // 旧算法用首段起点除以全文长度，有两个毛病
    // 一是短文只有一段不能滚，起点恒为0，永远0%
    // 二是长文滑到底时首项是最后一段，其起点必然小于全长，卡在97%或98%
    // 现在改为能滚就以已滚到底判100%，不能滚即视为已读完
    var savedPct by remember { mutableStateOf(article.progress) }
    var scrollable by remember { mutableStateOf(false) }

    // 视口里现在装了多少字符
    // 按可见项的像素占比加权累加，是实测值不是估的
    // 本项目正文只有纯文本段落，没有图片或代码块，所以按比例换算没有偏差
    fun visibleCharsNow(): Int {
        val info = listState.layoutInfo
        val vp = info.viewportEndOffset - info.viewportStartOffset
        if (vp <= 0) return 0
        var acc = 0f
        for (item in info.visibleItemsInfo) {
            val len = when {
                item.index + 1 < offsets.size -> offsets[item.index + 1] - offsets[item.index]
                else -> article.charCount - (offsets.getOrNull(item.index) ?: article.charCount)
            }
            if (len <= 0 || item.size <= 0) continue
            val top = maxOf(item.offset, info.viewportStartOffset)
            val bottom = minOf(item.offset + item.size, info.viewportEndOffset)
            if (bottom > top) acc += len * (bottom - top).toFloat() / item.size
        }
        return acc.toInt().coerceAtLeast(0)
    }

    // 当前读到第几个字符
    // 不能只取首段起点，短文章只有一两段时，进度只会在0%、50%、100%之间跳
    // 这里加上在首段里滚了百分之几的比例，首段600字滚过一半，就算读了300字
    fun readOffsetNow(): Int {
        val total = article.charCount
        if (total <= 0) return 0
        val idx = listState.firstVisibleItemIndex
        val base = offsets.getOrNull(idx) ?: return 0
        val nextOff = offsets.getOrNull(idx + 1) ?: total
        val paraLen = (nextOff - base).coerceAtLeast(0)
        val itemH = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == idx }?.size ?: 0
        val frac = if (itemH <= 0) 0f
        else (listState.firstVisibleItemScrollOffset.toFloat() / itemH).coerceIn(0f, 1f)
        return (base + paraLen * frac).toInt().coerceIn(0, total)
    }

    // 真正要写回文章的偏移
    // 滑到底或整篇一屏装得下就直接记全文末尾，
    // 否则只记首段起点的话，库里永远是97%，下次打开又跳回97%
    fun progressOffsetToSave(): Int {
        val total = article.charCount
        if (total <= 0) return 0
        if (!listState.canScrollForward) return total
        return readOffsetNow()
    }

    LaunchedEffect(listState, paragraphs.size) {
        snapshotFlow {
            // 带上scrollOffset，不加的话只有跨段时才发一次，段内滚动百分比不动
            listOf(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                if (listState.canScrollForward) 1 else 0,
                if (listState.canScrollBackward) 1 else 0
            )
        }.collect { v ->
            val canFwd = v[2] == 1
            val moved = v[3] == 1 || v[1] > 0
            val total = article.charCount
            scrollable = canFwd || moved
            val pct = when {
                total <= 0 -> 0f
                // 不能滚 = 一屏装得下 = 整篇都看得见
                !scrollable -> 1f
                // 已经滚到底
                !canFwd -> 1f
                else -> {
                    // 分母扣掉视口里这一屏，滚到底时分子正好到total，
                    // 进度自然等于1，不会再出现75%直接跳100%
                    // 上限封在0.99，分母扣了一屏，滚到末段时算出来可能已经大于等于1，
                    // 但此时还能继续滚，正文下面还有段间距和末尾那条线
                    // 提前显示100%会让用户觉得还没到底就完了
                    // 真正到底由上面的!canFwd分支给1，只差这1%，看不出来
                    val denom = (total - visibleCharsNow()).coerceAtLeast(1)
                    (readOffsetNow().toFloat() / denom).coerceIn(0f, 0.99f)
                }
            }
            // 按整数百分比节流，不加的话每滚一个像素都改状态，顶栏会一直重组
            if ((savedPct * 100).toInt() != (pct * 100).toInt()) savedPct = pct
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            onSaveProgress(progressOffsetToSave())
        }
    }
    LaunchedEffect(article.id) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            onSaveProgress(progressOffsetToSave())
        }
    }

    // 已收藏词的浅色底区间（只在开关打开时算）
    // 已收藏词的标注
    // 单词走token集合，快，词组必须走正则，否则Quantum computing这种
    // 多词条目永远匹配不上，收藏了却标不出来
    // 正文高亮只在开关打开时需要已收藏词集合
    // 关着时传空集，这样往下的remember不会被collectedState牵连重算
    val collectedWords = if (settings.markCollected) collectedState.value else emptySet()

    // 当前词卡那个词是否已收藏
    // 用derivedStateOf而不是直接读collectedState.value，
    // 后者会让整页订阅集合变了，收藏任何一个词都全页重跑
    // derivedStateOf把依赖收窄成这一个词在不在集合里，
    // 收藏别的词时结果没变，本页完全不重组
    val currentCollected by remember(lookup?.word) {
        derivedStateOf {
            val w = lookup?.word?.lowercase() ?: return@derivedStateOf false
            w in collectedState.value
        }
    }

    val collectedSingle = remember(collectedWords) {
        collectedWords.filterNot { it.contains(' ') }.toSet()
    }
    val collectedPhrases = remember(collectedWords) {
        collectedWords.filter { it.contains(' ') }
    }
    // 编译一次，往下传。不预编译的话每段每词组都要重编一遍
    val phraseRegexes = remember(collectedPhrases) { compilePhraseRegexes(collectedPhrases) }
    val softColor = remember(settings.theme, settings.appSeed) {
        settings.resolvedTheme().text.copy(alpha = 0.10f)
    }

    // 目标词，生成文章时指定要练的词，开关每篇独立存在Article上
    val targetRegexes = remember(article.targetWords, article.highlightTargets) {
        if (!article.highlightTargets) emptyList()
        else compilePhraseRegexes(article.targetList)
    }

    // 各项缓存
    val tabCache = remember { mutableStateMapOf<String, Any>() }

    val requestTab: (WordLookup, SenseTab) -> Unit = remember(apiKey, model) {
        { lk, tab ->
            // AI看的是上句加当前句加下句，但界面显示和出处仍用单句
            val ctx = lk.context?.takeIf { it.isNotBlank() } ?: lk.sentence
            // 缓存键必须同时包含所在句和上下文
            //
            // 只用context会撞，同一个词在同一段里出现两次时，
            // 两次的上句加本句加下句可能拼出完全相同的字符串，
            // 比如段落只有两句，点句0和句1的observed，上下文都是句0加句1，
            // 于是第二次直接命中第一次的缓存，弹出的是另一个位置的释义
            //
            // 所以用sentence做真正的键，context只作为附加信息，
            // 它变了说明上下文变了，答案也该重算
            val key = senseKey(lk.word, lk.sentence, ctx, tab.name)
            // 写回时必须基于当前lookup，不能用传进来的lk
            //
            // lk是发起请求那一刻的快照，连点两个tab时，第二个请求的lk
            // 里第一个tab还是Loading，如果第一个请求先返回、用它自己的lk写回，
            // 就会把第二个tab的状态冲回旧值，反过来也一样
            // 结果是某个tab永远停在Loading，它的请求早已结束，没人再更新它
            //
            // 守卫word，用户可能中途换词，A的结果不能写进B的卡片
            fun writeBack(st: TabState) {
                val cur = lookup ?: return
                // 只比word不够，同一个词可能出现在不同句子里
                // 点run（句子A），关卡片，点run（句子B）时，
                // A的返回如果只校验word，就会写进B的卡片，给出错误语境的释义
                // 所以word和context都要一致
                if (!cur.word.equals(lk.word, ignoreCase = true)) return
                // sentence和context都要一致
                // 只比context不够，同段两处的上下文可能相同，会互相串答案
                if (cur.sentence != lk.sentence) return
                if ((cur.context ?: cur.sentence) != (lk.context ?: lk.sentence)) return
                lookup = cur.withTab(tab, st)
            }

            val cached = tabCache[key]
            if (cached != null) {
                writeBack(TabState.Ok(cached))
            } else {
                writeBack(TabState.Loading)
                scope.launch {
                    // 先查磁盘缓存，跨文章跨重启都命中
                    val disk = cacheRepo?.get(lk.word, key, tab.name)
                    if (disk != null) {
                        val parsedDisk: Any? = parseByTab(tab, disk)
                        if (parsedDisk != null) {
                            tabCache[key] = parsedDisk
                            writeBack(TabState.Ok(parsedDisk))
                            return@launch
                        }
                    }
                    if (apiKey.isBlank()) {
                        writeBack(TabState.Err("还没有配置 DeepSeek 密钥"))
                        return@launch
                    }
                    // 多词选中 = 词组。词组必须用专用提示词，否则模型只解释核心词
                    val isPhrase = lk.word.trim().contains(' ')
                    val sys = when (tab) {
                        SenseTab.SENSE ->
                            if (isPhrase) Prompt.SENSE_PHRASE_SYSTEM else Prompt.SENSE_SYSTEM
                        SenseTab.ROOT -> Prompt.ROOT_SYSTEM
                        SenseTab.COLLO -> Prompt.COLLOCATION_SYSTEM
                        SenseTab.EXAMPLE -> Prompt.EXAMPLE_SYSTEM
                        SenseTab.PHONETIC -> Prompt.PHONETIC_SYSTEM
                    }
                    val dictGlossArg = lk.entry?.translation?.replace("\n", " / ") ?: "无"
                    val usr = if (tab == SenseTab.SENSE)
                        if (isPhrase) Prompt.phraseSenseUser(lk.word, ctx, dictGlossArg)
                        else Prompt.senseUser(lk.word, ctx, dictGlossArg)
                    else if (isPhrase) Prompt.phraseUser(lk.word, ctx)
                    else Prompt.baseUser(lk.word, ctx)

                    val r = DeepSeekClient.chat(
                        apiKey = apiKey, systemPrompt = sys, userPrompt = usr,
                        maxTokens = if (tab == SenseTab.SENSE) 512 else 640, model = model,
                        tag = "READER_${tab.name}"
                    )
                    val next: TabState = when (r) {
                        is AiResult.Ok -> {
                            val parsed: Any? = parseByTab(tab, r.content)
                            usageRepo?.record(tab.name, model, r.usage)
                            if (parsed != null) {
                                tabCache[key] = parsed
                                cacheRepo?.put(lk.word, key, tab.name, r.content, model)
                                TabState.Ok(parsed)
                            }
                            else TabState.Err("返回格式异常，再点一次")
                        }
                        is AiResult.Fail -> TabState.Err(aiErrorText(r.kind))
                    }
                    writeBack(next)
                }
            }
        }
    }

    val t = settings.resolvedTheme()
    // 正文字体。没选过就是衬线，和这个功能出现之前一样
    val bodyStyle = remember(settings.fontSize, settings.lineHeight, readingFamily) {
        TextStyle(
            fontSize = settings.fontSize.sp,
            lineHeight = (settings.fontSize * settings.lineHeight).sp,
            fontFamily = readingFamily ?: FontFamily.Serif
        )
    }
    // 段间距等于行高乘0.75
    // 0.75不是拍的，对主流博客排版实测，段距除以行高等于0.754
    // 原来写死14dp，26sp时行高57sp、段距还是14dp，段落会粘成一片
    val paraGap = remember(settings.fontSize, settings.lineHeight) {
        // 用dp不用sp，Modifier.height要Dp，而且段间距不该随系统字体缩放二次放大
        (settings.fontSize * settings.lineHeight * 0.75f).dp
    }
    val highlightColor = remember(t) { t.text.copy(alpha = 0.14f) }
    val underlineColor = remember(t) { t.text.copy(alpha = 0.55f) }


    // 多选操作条，放进topBar槽位而不是浮层，浮层会盖住正文第一行
    // 短文又没法往下滑，等于那句话永远看不到

    // 打开词卡时先把磁盘缓存里的语境义填上
    // 不填的话，点开卡，顶部显示词典可能未收录，用户点一下语境义，
    // 其实是命中缓存瞬间返回，然后才变成真正的释义，
    // 同一个词每次打开都要多点一下，反反复复
    //
    // 只预填SENSE，它喂的是顶部那块占位，其余四项不展开就不消耗，没必要预读
    suspend fun prefillCachedSense(lk: WordLookup) {
        val repo = cacheRepo ?: return
        val ctx = lk.context?.takeIf { it.isNotBlank() } ?: lk.sentence
        // 键必须和requestTab用的是同一个，否则这里永远预填不上，
        // 或者更糟，预填到另一处的答案
        val key = senseKey(lk.word, lk.sentence, ctx, SenseTab.SENSE.name)
        val raw = repo.get(lk.word, key, SenseTab.SENSE.name) ?: return
        val parsed = com.oracole.readit.data.ai.parseSense(raw) ?: return
        val cur = lookup ?: return
        // 和writeBack一样三道守卫，只比word会把A句的结果塞进B句的卡片
        if (!cur.word.equals(lk.word, ignoreCase = true)) return
        if (cur.sentence != lk.sentence) return
        if ((cur.context ?: cur.sentence) != (lk.context ?: lk.sentence)) return
        // 只在用户还没点过语境义时预填，不覆盖正在用的状态
        if (cur.sense is TabState.Idle) {
            lookup = cur.copy(sense = TabState.Ok(parsed))
        }
    }

    val selectionBar: @Composable () -> Unit = {
        SelectionBar(
            theme = t,
            wordCount = picked.size,
            isWholeSentence = wholeSentence != null,
            onCancel = { exitSelection() },
            onWholeSentence = {
                // 只扩展选中范围到整句，不清空picked，翻译和词组都要能接着用
                val base = picked.minByOrNull { it.first } ?: return@SelectionBar
                val para = paraList.firstOrNull {
                    base.first >= it.second && base.first < it.second + it.first.length
                } ?: return@SelectionBar
                val local = base.first - para.second
                val r = Tokenizer.sentenceRange(para.first, local, local + base.third.length)
                wholeSentence = (para.second + r.first) to (para.second + r.second)
            },
            onTranslate = {
                val range = wholeSentence
                // 高亮区间，整句模式是一个连续区间，离散选词是各个词各自的区间
                // 不能取最小到最大，那会把首词和末词之间没选中的词也框住
                val ranges: List<Pair<Int, Int>>
                val txt: String
                val s0: Int
                if (range != null) {
                    txt = article.content.substring(range.first, range.second)
                    ranges = listOf(range)
                    s0 = range.first
                } else {
                    // 必须在exitSelection之前取，它会把picked清空
                    val sorted = picked.sortedBy { it.first }
                    txt = sorted.joinToString(" ") { it.third }
                    ranges = sorted.map { it.first to it.second }
                    s0 = sorted.firstOrNull()?.first ?: 0
                }
                exitSelection()
                if (txt.isNotBlank()) {
                    sentenceSel = txt to ranges
                    // 缓存里已有这句的译文，就直接显示，不用再点一次翻译
                // 这是纯读取，不产生请求，没缓存就保持空，等用户主动点
                    translation = null
                    translationNote = null
                    val paraText = paraList.firstOrNull {
                        s0 >= it.second && s0 < it.second + it.first.length
                    }?.first ?: txt
                    scope.launch {
                        cacheRepo?.get(txt, paraText, "TRANSLATE")?.let { hit ->
                            com.oracole.readit.data.ai.parseTranslate(hit)?.let {
                                translation = it.translation
                                translationNote = it.note
                            }
                        }
                    }
                }
            },
            onPhrase = {
                // 词组，把选中的内容当作一个整体去查，走同一个释义卡片
                // 两种来源都要支持，整句模式用整句，否则用离散选中的词
                val range = wholeSentence
                val txt: String
                val highlight: List<Pair<Int, Int>>
                val s0: Int
                if (range != null) {
                    txt = article.content.substring(range.first, range.second)
                    highlight = listOf(range)
                    s0 = range.first
                } else {
                    txt = picked.sortedBy { it.first }.joinToString(" ") { it.third }
                    highlight = picked.sortedBy { it.first }.map { it.first to it.second }
                    s0 = picked.minOfOrNull { it.first } ?: 0
                }
                if (txt.isBlank()) return@SelectionBar
                // 整句模式下txt本身就是句子，否则从所在段里取当前句加上下文
                val (sentence, context) = if (range != null) {
                    txt to txt
                } else {
                    paraList.firstOrNull {
                        s0 >= it.second && s0 < it.second + it.first.length
                    }?.let {
                        val local = s0 - it.second
                        Tokenizer.sentenceAndContext(it.first, local, local + 1)
                    } ?: (txt to txt)
                }
                exitSelection()
                wordRanges = highlight
                lookup = WordLookup(
                    word = txt, sentence = sentence, context = context, loading = true
                )
                val lk0 = lookup!!
                scope.launch {
                    prefillCachedSense(lk0)
                    val en = dictRepo.lookup(txt)
                    if (lookup?.word == txt) {
                        lookup = lookup?.copy(entry = en, loading = false)
                    }
                }
            },
            onSpeak = {
                // 朗读当前选中的内容，整句模式读整句，否则读选中的词
                // 不清除选择，用户可以连续听，也可以接着改选区
                val txt = if (wholeSentence != null)
                    article.content.substring(wholeSentence!!.first, wholeSentence!!.second)
                else picked.sortedBy { it.first }.joinToString(" ") { it.third }
                if (txt.isNotBlank()) Tts.speak(txt, settings.ttsRate)
            },
            onCopy = {
                val txt = if (wholeSentence != null)
                    article.content.substring(wholeSentence!!.first, wholeSentence!!.second)
                else picked.sortedBy { it.first }.joinToString(" ") { it.third }
                if (txt.isNotBlank()) {
                    clipboard.setText(AnnotatedString(txt))
                    scope.launch { Notify.show("已复制") }
                }
                exitSelection()
            }
        )
    }

    Box(Modifier.fillMaxSize().background(t.bg)) {
        Scaffold(
            containerColor = t.bg,
            snackbarHost = { SnackbarHost(innerToast) },
            topBar = {
                // 多选时用操作条替换标题栏，它参与布局，正文被真实顶下去
                // 不会盖住第一行，AnimatedContent负责把高度过渡做平滑
                AnimatedContent(
                    targetState = selecting,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(110)) },
                    label = "readerTopBar"
                ) { sel ->
                    if (sel) {
                        selectionBar()
                    } else {
                TopAppBar(
                    title = {
                        // 顶栏空间被四个图标挤得很窄，长标题必然截断
                        // 用跑马灯，只在文字超出时才滚，而且同一屏只有这一个标题在动
                        // 不会像列表那样一堆文字一起滚
                        Text(
                            article.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            color = t.text,
                            modifier = Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1200,
                                repeatDelayMillis = 2400,
                                velocity = 28.dp
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = t.text)
                        }
                    },
                    actions = {
                        // 快捷项留在外面，其余收进三点菜单，给标题让出宽度
                        // 固定宽度，进度文字会从9%变到10%再到100%
                        // 宽度一变，标题可用宽度就跟着变，basicMarquee重新测量，标题跑回开头重滚
                        // 这是一滑动标题就重置的真正原因
                        Text(
                            if (savedPct >= 0.999f && !scrollable) "已读完"
                            else "${(savedPct * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = t.secondary,
                            maxLines = 1,
                            modifier = Modifier
                                .widthIn(min = 34.dp)
                                .padding(end = 2.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (article.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                                if (article.isFavorite) "取消收藏" else "收藏",
                                modifier = Modifier.size(22.dp),
                                tint = if (article.isFavorite) MaterialTheme.colorScheme.primary
                                else t.secondary.copy(alpha = 0.75f)
                            )
                        }
                        // 深浅色是两个并列状态，各自记住自己那套背景
    // 原来切到深色时硬编码ReaderTheme.Night，
                        // 用户在深色下挑的自定义色会被冲掉，切回来就变回默认深灰
                        IconButton(onClick = {
                            settings.toggleDark(!t.isDark)
                        }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (t.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                                if (t.isDark) "浅色" else "深色",
                                modifier = Modifier.size(22.dp),
                                tint = t.text
                            )
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.MoreVert, "更多",
                                    modifier = Modifier.size(22.dp), tint = t.text)
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("标注已收藏的词") },
                                    leadingIcon = {
                                        Icon(
                                            if (settings.markCollected) Icons.Filled.Bookmark
                                            else Icons.Outlined.BookmarkBorder, null
                                        )
                                    },
                                    trailingIcon = {
                                        Switch(
                                            checked = settings.markCollected,
                                            onCheckedChange = { settings.updateMarkCollected(it) }
                                        )
                                    },
                                    onClick = {
                                        settings.updateMarkCollected(!settings.markCollected)
                                    }
                                )
                                if (article.targetList.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("标注目标词") },
                                        leadingIcon = {
                                            Icon(
                                                if (article.highlightTargets) Icons.Filled.Bookmark
                                                else Icons.Outlined.BookmarkBorder, null
                                            )
                                        },
                                        trailingIcon = {
                                            Switch(
                                                checked = article.highlightTargets,
                                                onCheckedChange = { onToggleTargets(it) }
                                            )
                                        },
                                        onClick = { onToggleTargets(!article.highlightTargets) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("翻译全文") },
                                    leadingIcon = { Icon(Icons.Outlined.Translate, null) },
                                    onClick = { showMenu = false; onTranslateArticle() }
                                )
                                DropdownMenuItem(
                                    text = { Text("阅读设置") },
                                    leadingIcon = { Icon(Icons.Outlined.TextFields, null) },
                                    onClick = { showMenu = false; showSettings = true }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = t.bg)
                )
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(
                    start = settings.margin.dp, end = settings.margin.dp,
                    top = 8.dp, bottom = 120.dp
                )
            ) {
    // 高亮区间，抽出成函数，整段渲染和句级渲染都用它
                fun currentHighlightRanges(): List<Pair<Int, Int>> = when {
                    selecting ->
                        if (wholeSentence != null) listOfNotNull(wholeSentence)
                        else picked.map { it.first to it.second }
                    lookup != null -> wordRanges
                    sentenceSel != null -> sentenceSel?.second ?: emptyList()
                    else -> emptyList()
                }

                val onWordClickAt: (String, Int, Int, String, String) -> Unit = { w, s, e, sentence, context ->
                    if (selecting) {
                        if (wholeSentence != null) {
                            wholeSentence = null
                            picked = listOf(Triple(s, e, w))
                        } else {
                            picked = if (picked.any { it.first == s && it.second == e })
                                picked.filterNot { it.first == s && it.second == e }
                            else picked + Triple(s, e, w)
                            if (picked.isEmpty()) exitSelection()
                        }
                    } else {
                        exitSelection()
                        sentenceSel = null
                        wordRanges = listOf(s to e)
                        lookup = WordLookup(
                            word = w, sentence = sentence, context = context, loading = true
                        )
                        val lk0 = lookup!!
                        scope.launch {
                            prefillCachedSense(lk0)
                            val en = dictRepo.lookup(w)
                            if (lookup?.word == w) {
                                lookup = lookup?.copy(entry = en, loading = false)
                            }
                        }
                    }
                }

                val onLongPressAt: (String, Int, Int, String, String) -> Unit = { w, s, e, _, _ ->
                    lookup = null
                    sentenceSel = null
                    selecting = true
                    wholeSentence = null
                    picked = listOf(Triple(s, e, w))
                }

                itemsIndexed(paragraphs, key = { i, p -> "${i}_${p.hashCode()}" }) { i, para ->
                    val base = offsets[i]
                    ParagraphBody(
                        text = para,
                        baseOffset = base,
                        style = bodyStyle,
                        color = t.text,
                        collectedSingle = if (settings.markCollected) collectedSingle else emptySet(),
                        phraseRegexes = if (settings.markCollected) phraseRegexes else emptyList(),
                        softColor = softColor,
                        targetRegexes = if (article.highlightTargets) targetRegexes else emptyList(),
                        targetColor = t.text.copy(alpha = 0.42f),
                        flashRanges = flashRanges,
                        flashColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                        highlightRanges = currentHighlightRanges(),
                        selecting = selecting,
                        onWordClick = onWordClickAt,
                        onLongPressWord = onLongPressAt
                    )
                    Spacer(Modifier.height(paraGap))
                }
                item {
                    Spacer(Modifier.height(40.dp))
                    Text("— 完 —", modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center, color = t.secondary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

    // 回到阅读进度，从词汇详情跳过来后浮在右下角，点一下回到跳走前的位置
        androidx.compose.animation.AnimatedVisibility(
            visible = returnOffset != null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 28.dp),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it }
        ) {
            Surface(
                onClick = {
                    val off = returnOffset ?: return@Surface
                    val idx = offsets.indexOfLast { it <= off }.coerceAtLeast(0)
                    scope.launch {
                        listState.animateScrollToItem(idx)
                        returnOffset = null
                    }
                },
                shape = MaterialTheme.shapes.medium,
                color = t.text.copy(alpha = 0.92f),
                shadowElevation = 6.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.History, null, Modifier.size(16.dp), tint = t.bg)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "回到阅读进度",
                        style = MaterialTheme.typography.labelMedium,
                        color = t.bg
                    )
                }
            }
        }
    }

    // 整句卡片
    sentenceSel?.let { (sentence, range) ->
        SentenceSheet(
            sentence = sentence,
            readerTheme = t,
            onSpeak = { Tts.speak(sentence, settings.ttsRate) },
            translating = translating,
            translation = translation,
            note = translationNote,
            explaining = explaining,
            explain = explainResult,
            onExplain = { force ->
                scope.launch {
                // 用首个区间的起点找所在段落，不能取最小到最大，
                    // 离散选词时它们跨过的中间词不属于这次查询
                    val anchor = range.firstOrNull()?.first ?: 0
                    val para = paraList.firstOrNull {
                        anchor >= it.second && anchor < it.second + it.first.length
                    }?.first ?: sentence
                    val key = sentence + "|" + (translation ?: "")
                    if (!force) cacheRepo?.get(key, para, "EXPLAIN")?.let { hit ->
                        com.oracole.readit.data.ai.parseExplain(hit)?.let {
                            explainResult = it
                            return@launch
                        }
                    }
                    if (apiKey.isBlank()) {
                        Notify.show("还没有配置 DeepSeek 密钥")
                        return@launch
                    }
                    explaining = true
                    val r = DeepSeekClient.chat(
                        apiKey = apiKey,
                        systemPrompt = Prompt.EXPLAIN_SYSTEM,
                        userPrompt = Prompt.explainUser(sentence, translation ?: ""),
                        maxTokens = 700,
                        model = model
                    )
                    explaining = false
                    when (r) {
                        is AiResult.Ok -> {
                            usageRepo?.record("EXPLAIN", model, r.usage)
                            val parsed = com.oracole.readit.data.ai.parseExplain(r.content)
                            if (parsed != null) {
                                explainResult = parsed
                                cacheRepo?.put(key, para, "EXPLAIN", r.content, model)
                            } else Notify.show("解析返回格式异常")
                        }
                        is AiResult.Fail -> Notify.show(aiErrorText(r.kind))
                    }
                }
            },
            onDismiss = {
                sentenceSel = null
                translating = false; translation = null; translationNote = null
                explaining = false; explainResult = null
            },
            onTranslate = { force ->
                scope.launch {
                    translating = true
            // 上下文给整段，代词和一词多义靠它才能译准
            // 用首个区间的起点找所在段落，不能取最小到最大，
                    // 离散选词时它们跨过的中间词不属于这次查询
                    val anchor = range.firstOrNull()?.first ?: 0
                    val para = paraList.firstOrNull {
                        anchor >= it.second && anchor < it.second + it.first.length
                    }?.first ?: sentence
            // 缓存键是目标句加所在段
            // force为true时跳过缓存，用户点了重译就该真重译
                    val hit = if (force) null else cacheRepo?.get(sentence, para, "TRANSLATE")
                    if (hit != null) {
                        val parsed = com.oracole.readit.data.ai.parseTranslate(hit)
                        if (parsed != null) {
                            translation = parsed.translation
                            translationNote = parsed.note
                            translating = false
                            return@launch
                        }
                    }
                    if (apiKey.isBlank()) {
                        translating = false
                        Notify.show("还没有配置 DeepSeek 密钥")
                        return@launch
                    }
                    val r = DeepSeekClient.chat(
                        apiKey = apiKey,
                        systemPrompt = Prompt.TRANSLATE_SYSTEM,
                        userPrompt = Prompt.translateUser(sentence, para),
                        maxTokens = 640,
                        model = model
                    )
                    translating = false
                    when (r) {
                        is AiResult.Ok -> {
                            usageRepo?.record("TRANSLATE", model, r.usage)
                            val parsed = com.oracole.readit.data.ai.parseTranslate(r.content)
                            if (parsed != null) {
                                translation = parsed.translation
                                translationNote = parsed.note
                                cacheRepo?.put(sentence, para, "TRANSLATE", r.content, model)
                            } else Notify.show("翻译返回格式异常，再试一次")
                        }
                        is AiResult.Fail -> Notify.show(aiErrorText(r.kind))
                    }
                }
            },
            onCopy = {
                clipboard.setText(AnnotatedString(sentence))
                sentenceSel = null
            }
        )
    }

    // 语境义结果到达之后，才能拿到base
    // 单独放一个effect，放在onTabClick里的话，第一次点击时结果还没回来
    // base是null，记形态和精确补出处都会落空
    // key里不放collectedWords，addForm会改它，放进去会自己触发自己
    LaunchedEffect(lookup?.word, lookup?.sense, settings.collectByBase) {
        val cur = lookup ?: return@LaunchedEffect
        val base = (cur.sense as? TabState.Ok)
            ?.data?.let { (it as? com.oracole.readit.data.ai.SenseResult)?.base }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@LaunchedEffect
        if (base.equals(cur.word, ignoreCase = true)) return@LaunchedEffect
        if (!settings.collectByBase) return@LaunchedEffect

    // 已收藏的词，把当前形态记到原形那条下面，详情页那行绿字靠它
        onAddForm(base, cur.word)
    // 同时用base精确定位补出处，addContext按sentence去重，重复调用无副作用
        if (cur.word.lowercase() in collectedState.value) {
            onAddContext(cur.word, cur.sentence, cur.word, base)
        }
    }

    // 词汇卡片
    lookup?.let { lk ->
    // 按词本身判断，不做原形归约，collectedWords里现在同时装了
    // 原形和forms，所以收过running再点runs也能识别出来
        val lkBase = lk.word.lowercase()
        DefinitionSheet(
            lookup = lk,
            readerTheme = t,
            collected = currentCollected,
            toast = cardToast,
            onDismiss = { lookup = null; cardToast = null; wordRanges = emptyList() },
            onSpeak = { Tts.speak(lk.word, settings.ttsRate) },
            onFavorite = {
                if (lkBase in collectedState.value) {
            // 传当前语境的原形，found可能对应两条记录，需要精确指定删哪条
                    val curBase = (lk.sense as? TabState.Ok)
                        ?.data?.let { (it as? com.oracole.readit.data.ai.SenseResult)?.base }
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    onUncollect(lkBase, curBase)
                    cardToast = "已取消收藏"
                } else {
        // 词典有释义就用词典的，词典查不到（未收录词或词组）才用AI给的词典式释义
                    val dictTrans = lk.entry?.translation?.takeIf { it.isNotBlank() }
                    val aiGloss = (lk.sense as? TabState.Ok)
                        ?.data?.let { (it as? com.oracole.readit.data.ai.SenseResult)?.dictGloss }
                        ?.takeIf { it.isNotBlank() }
                    // AI结合上下文判定的原形，没有语境义结果时为空
                    // 上层会退回到文章里的形态
                    val aiBase = (lk.sense as? TabState.Ok)
                        ?.data?.let { (it as? com.oracole.readit.data.ai.SenseResult)?.base }
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    onCollect(
                        lk.word,
                        lk.entry?.phonetic,
                        dictTrans ?: aiGloss ?: "",
                        lk.sentence,
                        lk.word,
                        aiBase
                    )
                    cardToast = "已收藏"
                }
            },
            onTabClick = { tab ->
                val cur = lookup ?: return@DefinitionSheet

                // 这个词在当前语境下的原形，有语境义结果时才有
    // 下面两处都要用，记出处时用来精确定位记录，记形态时当作key
                val senseBase = (cur.sense as? TabState.Ok)
                    ?.data?.let { (it as? com.oracole.readit.data.ai.SenseResult)?.base }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                // 已收藏的词，点任意一项都记一笔出处（未收藏的词不处理，收藏时自会记）
                if (lkBase in collectedState.value) {
                // 传senseBase，found可能同时存在find的形态和独立的found
                // 两条记录，只给surface form分不清该记到哪条
                    onAddContext(lkBase, cur.sentence, cur.word, senseBase)
                }

    // 记形态和补出处都挪到下面的LaunchedEffect里
    // 不能在这里做，第一次点语境义时结果还没回来，cur.sense仍是Idle
    // senseBase恒为null，收藏后第一次点语境义，那行绿字不会出现
                val willOpen = tab !in cur.openTabs
                val base = cur.copy(
                    openTabs = if (willOpen) cur.openTabs + tab else cur.openTabs - tab
                )
                lookup = base
    // Idle是从没请求过
    // Err是上次失败，允许重试，否则文案里的再点一次是空话
    // Loading和Ok是不重复请求
                val st = base.stateOf(tab)
                if (willOpen && (st is TabState.Idle || st is TabState.Err)) {
                    requestTab(base, tab)
                }
            }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(settings = settings, onDismiss = { showSettings = false })
    }
}

@Composable
private fun SelectionBar(
    theme: ReaderTheme,
    wordCount: Int,
    isWholeSentence: Boolean,
    onCancel: () -> Unit,
    onWholeSentence: () -> Unit,
    onTranslate: () -> Unit,
    onPhrase: () -> Unit,
    onSpeak: () -> Unit,
    onCopy: () -> Unit
) {
            // 用阅读主题色，不用MaterialTheme，后者是全局主题，跟阅读背景不搭
    Surface(color = theme.bg, tonalElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close, "取消选择",
                        modifier = Modifier.size(20.dp),
                        tint = theme.text
                    )
                }
                Text(
                    if (isWholeSentence) "整句" else "已选 $wordCount 个词",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.secondary
                )
                Spacer(Modifier.weight(1f))
            }
        // 五个按钮等距排列，每个weight(1f)，内边距压到2dp
        // 用weight而不是SpaceBetween，是为了让图标加文字各自居中成一列
            // 不至于因为文字长度不同而疏密不均
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val cp = PaddingValues(horizontal = 2.dp)
                BarAction(Icons.Outlined.FormatQuote, "全句", onWholeSentence, cp, theme.text, Modifier.weight(1f))
                BarAction(Icons.Outlined.VolumeUp, "朗读", onSpeak, cp, theme.text, Modifier.weight(1f))
                BarAction(Icons.Outlined.Translate, "翻译", onTranslate, cp, theme.text, Modifier.weight(1f))
                BarAction(Icons.Outlined.MenuBook, "词组", onPhrase, cp, theme.text, Modifier.weight(1f))
                BarAction(Icons.Outlined.ContentCopy, "复制", onCopy, cp, theme.text, Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    contentPadding: PaddingValues,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onClick, contentPadding = contentPadding, modifier = modifier) {
        Icon(icon, null, Modifier.size(17.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(label, maxLines = 1, softWrap = false,
            style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)

/**
 * 在段落里找出所有目标词出现的位置
 *
 * 不能用Tokenizer.tokens()，它按单词切，quantum computing会被拆成两个词
 * 跟目标词表里的整个词组永远对不上，于是多词目标词全都标不出来
 *
 * 这里按短语匹配：
 *   - 目标词内部的空格容许任意空白（应对换行、多空格）
 * 忽略大小写，收藏里存的是Quantum computing，正文是小写
 * 用词边界包住，避免gene命中general
 */
/**
 * 把词组列表编译成正则
 *
 * 必须和匹配分开，原来findPhraseRanges每调用一次都对每个词组重新编译一遍
 * 而它是按段落调的，100个词组、200段落，就是两万次正则编译
 * 编译一次存起来，之后每段只做匹配
 */
private fun compilePhraseRegexes(targets: List<String>): List<Regex> {
    val out = ArrayList<Regex>(targets.size)
    for (raw in targets) {
        val t = raw.trim()
        if (t.isEmpty()) continue
        val body = t.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        // 前后必须是词边界，但目标词本身可能以连字符结尾（human-driven）
        val pattern = "(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])"
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()?.let { out.add(it) }
    }
    return out
}

private fun findPhraseRanges(text: String, regexes: List<Regex>): List<Pair<Int, Int>> {
    if (regexes.isEmpty()) return emptyList()
    val out = ArrayList<Pair<Int, Int>>()
    for (re in regexes) {
        re.findAll(text).forEach { m ->
            if (m.range.last >= m.range.first) {
                out.add(m.range.first to (m.range.last + 1))
            }
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(settings: ReaderSettings, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = settings.resolvedTheme().bg) {
        ReaderSettingsContent(settings)
    }
}

/** AI 错误类型 → 人话 */
/**
 * 语境义的缓存键
 *
 * 四个成分缺一不可：
 *
//   word      换词当然要换答案
//   sentence  所在句，只用context会撞，同段里同一个词出现两次时
 * 两次的上句+本句+下句可能拼出完全相同的字符串
//   比如段落只有两句，点句0和句1的observed
 * 上下文都是句0+句1，第二次就会拿到第一次的答案
//   context   上下文，它变了说明AI看到的材料变了，答案也该重算
//   tab       语境义、词根、搭配、例句、音标，各自独立
 *
// 拼成一串后交给AiCacheRepository存进sentence_hash字段，实际存的是它的SHA-1
 */
internal fun senseKey(word: String, sentence: String, context: String, tab: String): String =
    "${word.lowercase()}|${sentence.hashCode()}|${context.hashCode()}|$tab"

private fun aiErrorText(kind: ErrorKind): String = when (kind) {
    ErrorKind.NO_KEY -> "还没有配置 DeepSeek 密钥"
    ErrorKind.INVALID_KEY -> "密钥无效，请检查后重新填写"
    ErrorKind.INSUFFICIENT_BALANCE -> "账户余额不足"
    ErrorKind.RATE_LIMIT -> "请求太频繁，等几秒再试"
    ErrorKind.NETWORK -> "网络没连上"
    ErrorKind.TIMEOUT -> "服务器没响应"
    ErrorKind.EMPTY_CONTENT -> "返回内容为空，再试一次"
    ErrorKind.TRUNCATED -> "返回被截断，稍后重试"
    ErrorKind.PARSE_ERROR -> "返回格式异常，稍后重试"
    ErrorKind.UNKNOWN -> "生成失败，稍后重试"
}

/** 按 tab 解析 AI 返回的 JSON，失败返回 null */
private fun parseByTab(tab: SenseTab, raw: String): Any? = when (tab) {
    SenseTab.SENSE -> com.oracole.readit.data.ai.parseSense(raw)
    SenseTab.ROOT -> com.oracole.readit.data.ai.parseRoot(raw)
    SenseTab.COLLO -> com.oracole.readit.data.ai.parseCollocations(raw)
    SenseTab.EXAMPLE -> com.oracole.readit.data.ai.parseExamples(raw)
    SenseTab.PHONETIC -> com.oracole.readit.data.ai.parsePhonetic(raw)
}


/**
 * 段落正文
    // baseOffset是这段在全文中的起始偏移，用于把整段的区间换算到本段内
 */
@Composable
private fun ParagraphBody(
    text: String,
    baseOffset: Int,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    collectedSingle: Set<String>,
    phraseRegexes: List<Regex>,
    softColor: androidx.compose.ui.graphics.Color,
    targetRegexes: List<Regex>,
    targetColor: androidx.compose.ui.graphics.Color,
    flashRanges: List<Pair<Int, Int>>,
    flashColor: androidx.compose.ui.graphics.Color,
    highlightRanges: List<Pair<Int, Int>>,
    selecting: Boolean,
    onWordClick: (String, Int, Int, String, String) -> Unit,
    onLongPressWord: (String, Int, Int, String, String) -> Unit
) {
    // 本段的已收藏词标注
    //
    // 关键，只在本段范围内算，不再从全文那个大List里筛，
    // 后者是M段乘N区间，而且LazyColumn只组合可见段落
    // 所以收藏一个词时只有屏幕上这几段重算，不是整篇文章
    val softRanges: List<Pair<Int, Int>> = remember(text, baseOffset, collectedSingle, phraseRegexes) {
        if (collectedSingle.isEmpty() && phraseRegexes.isEmpty()) emptyList()
        else {
            val singles = if (collectedSingle.isEmpty()) emptyList()
            else Tokenizer.tokens(text)
                .filter { it.text.lowercase() in collectedSingle }
                .map { (baseOffset + it.start) to (baseOffset + it.end) }
            val phrases = findPhraseRanges(text, phraseRegexes)
                .map { (a, b) -> (baseOffset + a) to (baseOffset + b) }
            singles + phrases
        }
    }

    val targetRanges: List<Pair<Int, Int>> = remember(text, baseOffset, targetRegexes) {
        if (targetRegexes.isEmpty()) emptyList()
        else findPhraseRanges(text, targetRegexes)
            .map { (a, b) -> (baseOffset + a) to (baseOffset + b) }
    }

    ParagraphText(
        text = text,
        style = style,
        color = color,
        letterSpacing = 0.2.sp,
        highlightColor = color.copy(alpha = 0.14f),
        underlineColor = color.copy(alpha = 0.55f),
        softRanges = softRanges,
        softColor = softColor,
        targetRanges = targetRanges,
        targetColor = targetColor,
        flashRanges = flashRanges,
        flashColor = flashColor,
        highlightRanges = highlightRanges,
        baseOffset = baseOffset,
        onWordClick = onWordClick,
        onLongPressWord = onLongPressWord
    )
}
