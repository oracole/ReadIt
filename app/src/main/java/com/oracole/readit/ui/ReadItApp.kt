package com.oracole.readit.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.oracole.readit.data.Article
import com.oracole.readit.data.ArticleRepository
import com.oracole.readit.data.DictRepository
import com.oracole.readit.data.DictState
import com.oracole.readit.data.ImportResult
import com.oracole.readit.data.Settings
import com.oracole.readit.data.TxtImporter
import com.oracole.readit.data.VocabItem
import com.oracole.readit.data.VocabRepository
import com.oracole.readit.ui.component.ImportSheet
import com.oracole.readit.ui.reader.ReaderScreen
import com.oracole.readit.ui.theme.Motion
import com.oracole.readit.ui.theme.enterFor
import com.oracole.readit.ui.theme.exitFor
import com.oracole.readit.ui.reader.ReaderSettings
import com.oracole.readit.ui.component.TaskPill
import com.oracole.readit.ui.screen.AboutScreen
import com.oracole.readit.ui.screen.AiSettingsScreen
import com.oracole.readit.ui.screen.DictSettingsScreen
import com.oracole.readit.ui.screen.PasteScreen
import com.oracole.readit.ui.screen.ReadingSettingsScreen
import com.oracole.readit.ui.screen.SettingsScreen
import com.oracole.readit.data.ai.AiCacheRepository
import com.oracole.readit.data.ai.DeepSeekClient
import com.oracole.readit.data.ai.AiResult
import com.oracole.readit.data.ai.Prompt
import com.oracole.readit.data.ai.parseGenerated
import com.oracole.readit.data.ai.AiUsageRepository
import com.oracole.readit.util.Notify
import com.oracole.readit.util.Tts
import com.oracole.readit.data.ai.ArticleTranslator
import com.oracole.readit.data.ai.splitParagraphs
import com.oracole.readit.ui.screen.GenerateArticleScreen
import com.oracole.readit.ui.screen.TranslateArticleScreen
import com.oracole.readit.ui.screen.ShelfScreen
import com.oracole.readit.ui.screen.UsageScreen
import com.oracole.readit.ui.screen.VocabImportScreen
import com.oracole.readit.ui.screen.VocabDetailScreen
import com.oracole.readit.ui.screen.VocabSelectionBar
import com.oracole.readit.ui.screen.VocabScreen
import kotlinx.coroutines.launch

private data class Tab(val label: String, val filled: ImageVector, val outlined: ImageVector)

private val TABS = listOf(
    Tab("书架", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    Tab("词汇", Icons.Filled.Star, Icons.Outlined.Star),
    Tab("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

// 页面路径
// 带复杂参数的页面只把id放进路径，比如Generate的词表、Reader的定位锚点，
// 其余参数由外层的状态承载，路径保持简单可比较，也让状态集中在同一处
private fun Route.path(): String = when (this) {
    Route.Main -> "main"
    Route.Paste -> "paste"
    Route.AiSettings -> "ai"
    Route.DictSettings -> "dict"
    Route.ReadingSettings -> "reading"
    Route.Appearance -> "appearance"
    Route.AiLog -> "ailog"
    Route.Font -> "font"
    Route.Usage -> "usage"
    Route.About -> "about"
    Route.VocabImport -> "vocabImport"
    is Route.Reader -> "reader/$articleId"
    is Route.VocabDetail -> "vocab/$vocabId"
    is Route.Generate -> "generate"
    is Route.Translate -> "translate/$articleId"
}

private sealed interface Route {
    data object Main : Route
    data object Paste : Route
    data object AiSettings : Route
    data object DictSettings : Route
    data object ReadingSettings : Route
    data object Appearance : Route
    data object AiLog : Route
    data object Font : Route
    data object Usage : Route
    data object About : Route
    data object VocabImport : Route
    data class Reader(
        val articleId: Long,
        /** 从词汇详情跳过来时，要定位到的句子与该词 */
        val anchorSentence: String? = null,
        val anchorWord: String? = null
    ) : Route
    data class VocabDetail(val vocabId: Long) : Route
    data class Generate(val words: List<String>) : Route
    data class Translate(val articleId: Long) : Route
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadItApp(
    appearance: com.oracole.readit.ui.theme.AppearanceSettings
) {
    val ctx = LocalContext.current
    remember(ctx) { Notify.init(ctx); true }
    val scope = rememberCoroutineScope()

    // 词汇页多选状态提到这里，底栏要在dock和多选操作条之间二选一，
    // 放在VocabScreen内部的话，两个底栏会叠在一起，或者操作条浮在dock上面
    var vocabSelecting by remember { mutableStateOf(false) }
    val vocabSelected = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    var vocabFilter by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    val repo = remember { ArticleRepository(ctx) }
    val settings = remember { Settings(ctx) }
    val readerSettings = remember { ReaderSettings(ctx) }
    val dictRepo = remember { DictRepository(ctx) }

    val vocabRepo = remember { VocabRepository(ctx) }
    val usageRepo = remember { AiUsageRepository(ctx) }
    val cacheRepo = remember { AiCacheRepository(ctx) }
    // 收藏一个词，不做原形归约，点running收的就是running
    //
    // 为什么放弃归约，实测35个常见变形词里33个有自己独立的词性和义项，
    // 比如left是左边的、saw是锯子、found是建立、works是工厂、means是手段
    // 归一必须二选一保留哪个释义，无论选哪个都是丢信息
    // 更糟的是音标和释义可能来自不同词条，run挂着/ˈrʌnɪŋ/就是这么来的
    //
    // 代价是列表里可能出现run和running两条，但每条都准确
    suspend fun collectNormalized(
        word: String,
        phonetic: String?,
        translation: String,
        sentence: String,
        articleId: Long?,
        articleTitle: String?,
        wordForm: String?,
        /**
         * AI结合上下文判定的原形，null表示按文章里的形态存
         *
         * 默认不归约，下面那段注释说明了为什么
         * 只有用户打开按原形收藏、且AI确实给出原形时才用它
         */
        base: String? = null
    ) {
        // 真发生归约时，音标和释义也必须换成原形的
        //
        // 只改word不改这两项的话会出现两种情况，
        // break配地形突变、岩层中之小断层（break的复数），
        // mean配/miːnz/（means的音标），
        // 因为查的是句中那个变形词的词条
        var ph = phonetic
        var tr = translation
        if (base != null && !base.equals(word, ignoreCase = true)) {
            val e = dictRepo.lookup(base)
            if (e != null) {
                e.phonetic?.takeIf { it.isNotBlank() }?.let { ph = it }
                e.translation.takeIf { it.isNotBlank() }?.let { tr = it }
            }
            // 查不到就保留原来的，总比空着强
        }

        vocabRepo.collect(
            word = word,
            phonetic = ph,
            translation = tr,
            sentence = sentence,
            articleId = articleId,
            articleTitle = articleTitle,
            wordForm = wordForm,
            base = base
        )
    }

    // 这四个状态流只持有State对象，不在这里读值
    //
    // 为什么，by会在本函数里立即读值，于是任何一处数据变化都会让整个ReadItApp重跑，
    // 比如收藏一个词、保存一次进度、AI请求记一笔用量
    // ReadItApp里挂着NavHost，它的builder lambda捕获了这些值，
    // lambda变成新实例，remember的key就变了，整个导航图重建，
    // createGraph跑一遍，所有destination的lambda重建，页面全部重跑
    //
    // 结果是在一个长文里收藏一个词，整篇正文要重画一遍，卡零点几秒
    //
    // 改成持有State对象后，对象本身是同一个实例，lambda捕获它不会失效，
    // 只有在真正读取value的那个composable里才触发重组
    val usageState = usageRepo.summary.collectAsStateWithLifecycle()
    val articlesState = repo.articles.collectAsStateWithLifecycle()
    val vocabItemsState = vocabRepo.items.collectAsStateWithLifecycle()
    val collectedState = vocabRepo.collected.collectAsStateWithLifecycle()

    var dictState by remember { mutableStateOf<DictState>(DictState.NotImported) }

    var index by rememberSaveable { mutableIntStateOf(0) }
    // 记住上一次的tab，用于决定滑动方向，是向右切还是向左切
    var lastIndex by remember { mutableIntStateOf(index) }
    // ---- 导航 ----
    // 用Navigation Compose而不是自己维护一个List，
    // 它自带转场、自带返回栈，从哪来回哪去不用每页自己写
    val navController = androidx.navigation.compose.rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    // 复杂参数不能塞进路径，由这两个承载，navigate时先写好再跳
    var generateWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var readerAnchor by remember { mutableStateOf<Pair<String?, String?>?>(null) }

    // 当前所在页，给浮条判断、错误清理、返回键这类地方用
    // 不用remember，这个值要跟着栈实时变，加remember之后
    // 依赖它的协程，比如生成和翻译完成的回调，会拿到启动那一刻的旧值
    val route: Route = run {
        val dest = backStackEntry?.destination
        val id = backStackEntry?.arguments?.getLong("id") ?: 0L
        when (dest?.route) {
            "main" -> Route.Main
            "paste" -> Route.Paste
            "ai" -> Route.AiSettings
            "dict" -> Route.DictSettings
            "reading" -> Route.ReadingSettings
            "appearance" -> Route.Appearance
            "ailog" -> Route.AiLog
            "font" -> Route.Font
            "usage" -> Route.Usage
            "about" -> Route.About
            "vocabImport" -> Route.VocabImport
            "reader/{id}" -> {
                val a = readerAnchor
                Route.Reader(id, a?.first, a?.second)
            }
            "vocab/{id}" -> Route.VocabDetail(id)
            "generate" -> Route.Generate(generateWords)
            "translate/{id}" -> Route.Translate(id)
            else -> Route.Main
        }
    }

    // 当前是不是停在这个页面上
    // 给协程回调用的，协程跨越的时间很长，直接读闭包里的route会拿到旧值
    // 这里每次都问NavController，问的是现在
    fun onPage(destRoute: String): Boolean =
        navController.currentDestination?.route == destRoute

    /** 进入一个页面 */
    fun go(r: Route) {
        when (r) {
            is Route.Generate -> generateWords = r.words
            is Route.Reader -> readerAnchor = r.anchorSentence to r.anchorWord
            else -> Unit
        }
        if (route.path() == r.path()) return
        navController.navigate(r.path())
    }

    /** 返回上一页。栈空时不做（Main 由另一个 BackHandler 处理退出） */
    fun back() {
        if (navController.previousBackStackEntry != null) navController.popBackStack()
    }

    /** 用结果页顶掉当前操作页。做完事直接看结果，返回时不该再回到操作页 */
    fun replace(r: Route) {
        when (r) {
            is Route.Generate -> generateWords = r.words
            is Route.Reader -> readerAnchor = r.anchorSentence to r.anchorWord
            else -> Unit
        }
        val cur = navController.currentDestination?.route
        if (cur == r.path()) return
        navController.navigate(r.path()) {
            if (cur != null) popUpTo(cur) { inclusive = true }
        }
    }
    var showImportSheet by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var genError by remember { mutableStateOf<String?>(null) }
    // 全文翻译
    var translatingArticle by remember { mutableStateOf(false) }
    var translateProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var translateError by remember { mutableStateOf<String?>(null) }
    var translateTargetId by remember { mutableStateOf<Long?>(null) }
    // 翻译跑完时用户不在翻译页，留一个可点的入口，否则只弹一条toast，译文找不到
    var translatedReadyId by remember { mutableStateOf<Long?>(null) }
    // 用户自己从书架点开了那篇译文，入口就该消失，
    // 否则它会一直挂着，变成曾经生成过而不是有一篇等着你看
    LaunchedEffect(route) {
        val r = route
        if (r is Route.Reader && r.articleId == translatedReadyId) translatedReadyId = null
    }
    // 5秒后自己收起来，它只是个提示，不是待办事项，
    // 一直挂着会挡正文，也会让人以为还有事情没做完
    LaunchedEffect(translatedReadyId) {
        if (translatedReadyId != null) {
            kotlinx.coroutines.delay(5_000)
            translatedReadyId = null
        }
    }
    // 正在跑的生成请求，一是离开页面后回来还原配置，二是书架和词汇页显示置顶提示
    var genTask by remember { mutableStateOf<com.oracole.readit.ui.screen.GenRequest?>(null) }

    LaunchedEffect(Unit) {
        repo.load()
        // 文章文件解析失败时明确告知
        // 静默当空库最危险，用户以为文章丢了，导入一篇新的就把原文件覆盖掉
        if (repo.corruptedBackupKept) {
            Notify.show("原文章文件无法解析，已留档为 corrupt 备份")
        }
        dictRepo.open()
        // 词汇列表必须在这里加载
        // 之前删自动合并代码时把这一行一起删了，只剩注释，
        // 表现就是进应用时收藏页是空的，收藏一个词才出来
        vocabRepo.load()
        usageRepo.refresh()
        dictState = dictRepo.state()
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                when (val r = TxtImporter.read(ctx, uri)) {
                    is ImportResult.Ok -> {
                        // 存不下时add会抛异常，别让界面报已导入
                        val a = runCatching { repo.add("", r.text, r.fileName) }
                        a.getOrNull()?.let { Notify.show("已导入 · ${it.wordCount} 词") }
                            ?: Notify.show("保存失败，存储空间可能不足")
                    }
                    is ImportResult.Err -> Notify.show(r.message)
                }
            }
        }
    }

    // 系统返回键和页面自己的返回按钮走同一个动作，弹栈
    // 有上一页时接管返回键，Main上的再按一次退出由下一个BackHandler管
    BackHandler(enabled = navController.previousBackStackEntry != null) { back() }

    // 错误提示属于各自页面，离开就清，集中在四处，不再散落在每个onBack里
    LaunchedEffect(route) {
        if (route !is Route.Translate) translateError = null
        if (route !is Route.Generate) genError = null
    }

    var backArmedAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = route == Route.Main) {
        val now = System.currentTimeMillis()
        if (now - backArmedAt < 2000) {
            (ctx as? android.app.Activity)?.finish()
        } else {
            backArmedAt = now
            scope.launch { Notify.show("再按一次返回退出") }
        }
    }

    // 包一层Box，让悬浮条能盖在任何页面之上
    // 之前它在Main分支内部，所以只有书架页看得到，
    // 从翻译页退回原文时，进度和入口一起消失
    // 背景必须是实色，转场期间两页同时在屏上，
    // 任何一页透出底色就会看到黑屏，那是窗口默认背景
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 用NavHost承载页面，转场用Material官方的SharedAxis
        //
        // 这几行不是自己调的参数，materialSharedAxisX是
        // material-motion-compose提供的，它声明是对
        // com.google.android.material.transition.MaterialSharedAxis的移植，
        // 也就是Google自家app用的那一套
        //
        // 它内部的做法是，进入页从slideDistance处滑到0，同时淡入，延后一点开始，
        // 离开页只滑出slideDistance的35%，同时快速淡出
        // 离开页只走三分之一这一条就是视差感的来源，
        // 旧页面不是整块被推走，而是被新页面盖过去
        //
        // 时长300ms，缓动FastOutSlowIn，位移距离30dp，都是官方给定的
        // 转场风格在设置里选，改完立刻生效
        // 五种候选的具体做法见ui/theme/PageTransitions.kt
        // 这里只负责按当前选中的风格取参数

    // 跟随应用主题的阅读背景要按当前主色现算，这里把种子同步过去
    // 放在组合里而不是设置页里，换主色后阅读页可能已经开着，
    // 必须靠重组把新种子带过去
    androidx.compose.runtime.LaunchedEffect(appearance.seed) {
        readerSettings.appSeed = appearance.seed
    }

    // 接口地址同步给请求层
    // DeepSeekClient有九个调用点，给每个都加一个地址参数没有意义
    // 一次运行里它们必然用同一份配置，所以放全局，改设置时同步一次
    androidx.compose.runtime.LaunchedEffect(settings.baseUrl, settings.apiPath) {
        com.oracole.readit.data.ai.DeepSeekClient.endpoint = settings.baseUrl + settings.apiPath
    }

    val style = settings.transitionStyle
        androidx.navigation.compose.NavHost(
            navController = navController,
            startDestination = "main",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { enterFor(style, forward = true) },
            exitTransition = { exitFor(style, forward = true) },
            popEnterTransition = { enterFor(style, forward = false) },
            popExitTransition = { exitFor(style, forward = false) },
        ) {
            composable("paste") { entry ->
                PasteScreen(
                    onBack = { back() },
                    onConfirm = { title, text ->
                        scope.launch {
                            val a = runCatching { repo.add(title, text, null) }
                            back()
                            a.getOrNull()?.let { Notify.show("已导入 · ${it.wordCount} 词") }
                                ?: Notify.show("保存失败，存储空间可能不足")
                        }
                    }
                )
            }

            composable("dict") { entry ->
                DictSettingsScreen(
                    repo = dictRepo,
                    onBack = {
                        back()
                        scope.launch { dictState = dictRepo.state() }
                    },
                    // 导入/移除完成后立刻刷新，不用等退出再进
                    onStateChanged = {
                        scope.launch { dictState = dictRepo.state() }
                    }
                )
            }

            composable("about") { entry ->
                AboutScreen(onBack = { back() })
            }

            composable("vocabImport") { entry ->
                VocabImportScreen(
                    dictRepo = dictRepo,
                    vocabRepo = vocabRepo,
                    aiConfigured = settings.apiKey.isNotBlank(),
                    aiMeaning = { w ->
                        // 词典式释义，和查词页那个AI页签同一条提示词
                        // 导入的词没有句子，用不了语境义那套
                        val r = DeepSeekClient.chat(
                            apiKey = settings.apiKey,
                            systemPrompt = Prompt.SEARCH_ASK_SYSTEM,
                            userPrompt = w,
                            maxTokens = 600,
                            model = settings.model,
                            tag = "VOCAB_IMPORT"
                        )
                        if (r is AiResult.Ok) {
                            usageRepo.record("IMPORT", settings.model, r.usage)
                            val a = com.oracole.readit.data.ai.parseSearchAnswer(r.content)
                            a?.let { ans ->
                                if (ans.isWord) com.oracole.readit.data.ai.senseSummary(ans.senses)
                                else ans.text
                            }?.takeIf { it.isNotBlank() }
                        } else null
                    },
                    onBack = { back() },
                    onImported = { scope.launch { vocabRepo.load() } }
                )
            }

            composable("usage") { entry ->
                val usage by usageState
                UsageScreen(
                    summary = usage,
                    onBack = { back() },
                    onClear = { scope.launch { usageRepo.clearAll() } }
                )
            }

            composable("generate") { entry ->
                val r = Route.Generate(generateWords)
                GenerateArticleScreen(
                    words = r.words,
                    initial = genTask?.takeIf { it.words == r.words },
                    generating = generating,
                    error = genError,
                    onBack = { back() },
                    onGenerate = { taskRaw ->
                        val task = taskRaw
                        val level = runCatching {
                            listOf("A1","A2","B1","B2","C1","C2")[task.levelIdx]
                        }.getOrDefault("B1")
                        val length = task.length
                        val customLevel = if (task.customLevelOn) task.customLevel else ""
                        val customLength = task.customLength
                        val req = task.requirement
                        val topic = task.topic
                        val blend = task.blend
                        val thinking = when (task.thinkingIdx) {
                            0 -> null
                            1 -> "low"
                            2 -> "high"
                            else -> "max"
                        }
                        genTask = task
                        scope.launch {
                            generating = true
                            genError = null
                            // max_tokens是输出上限，而输出里不止正文，
                            // 还有JSON骨架和used、skipped两份词表，正文大概每词1.4个token，
                            // 开启思考时思考链的token也计入这个上限
                            // 之前只按正文字数算，一开思考就被挤爆，于是400词也报太长被截断
                            val words = if (customLength.isNotBlank()) 600 else length
                            val thinkBudget = when (thinking) {
                                null -> 0
                                "low" -> 1200
                                "high" -> 3000
                                else -> 6000
                            }
                            val jsonOverhead = 400 + r.words.size * 24
                            val maxTok = (words * 1.6f + jsonOverhead + thinkBudget)
                                .toInt().coerceIn(1024, 16000)
                            val r2 = DeepSeekClient.chat(
                                apiKey = settings.apiKey,
                                systemPrompt = Prompt.GENERATE_SYSTEM,
                                userPrompt = Prompt.generateUser(
                                    r.words, level, length,
                                    customLevel, customLength, req, topic, blend
                                ),
                                maxTokens = maxTok,
                                model = settings.model,
                                timeoutMs = 120_000,
                                thinkingLevel = thinking,
                                tag = "GENERATE"
                            )
                            generating = false
                            when (r2) {
                                is AiResult.Ok -> {
                                    usageRepo.record("GENERATE", settings.model, r2.usage)
                                    val g = parseGenerated(r2.content)
                                    if (g == null) {
                                        genError = "返回格式异常，再试一次"
                                    } else {
                            // 目标词用用户选中的全部词，不用AI返回的used
                            // used只用于统计实际用了几个，用它当目标词的话，
                            // AI一旦漏报，尤其是do not、moves from这类词组
                                        // 这些词就永远不会在正文里被标注
                                        val saved = runCatching {
                                            repo.add(g.title, g.content, "generated", r.words)
                                        }
                                        genError = null
                                        genTask = null
                                        // 还停在生成页才跳过去，已经离开的话只提示，不打断当前操作
                                        saved.getOrNull()?.let { a ->
                                            // 读实时的当前页，协程可能已经跑了很久
                            // 闭包里的route是启动那一刻的快照
                            if (onPage("generate")) replace(Route.Reader(a.id))
                                            Notify.show(
                                                "已生成《${a.title}》· 实际使用 ${g.used.size} / ${r.words.size} 个词"
                                            )
                                        } ?: Notify.show("保存失败，存储空间可能不足")
                                    }
                                }
                                is AiResult.Fail -> {
                                    genError = when (r2.kind) {
                                        com.oracole.readit.data.ai.ErrorKind.NO_KEY -> "还没有配置 DeepSeek 密钥"
                                        com.oracole.readit.data.ai.ErrorKind.INVALID_KEY -> "密钥无效"
                                        com.oracole.readit.data.ai.ErrorKind.INSUFFICIENT_BALANCE -> "余额不足"
                                        com.oracole.readit.data.ai.ErrorKind.TIMEOUT -> "超时了，再试一次"
                                        com.oracole.readit.data.ai.ErrorKind.TRUNCATED -> "文章太长被截断了，把篇幅调小一点"
                                        else -> "生成失败，再试一次"
                                    }
                                    // 已经离开生成页时，错误只在页面里看不到，补一条全局提示
                                    if (!onPage("generate")) {
                                        Notify.show(genError ?: "生成失败")
                                    }
                                }
                            }
                        }
                    }
                )
            }

            composable(
                "translate/{id}",
                // 显式声明成Long，默认是String，getLong取不到值会返回0
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
            ) { entry ->
                val r = Route.Translate(entry.arguments?.getLong("id") ?: 0L)
                val articles by articlesState
                val target = articles.firstOrNull { it.id == r.articleId }
                if (target == null) {
                    LaunchedEffect(Unit) { back() }
                } else {
                    val paras = remember(target.id) { splitParagraphs(target.content) }
                    TranslateArticleScreen(
                        articleId = target.id,
                        title = target.title,
                        wordCount = target.wordCount,
                        paraCount = paras.size,
                        translating = translatingArticle,
                        progress = translateProgress,
                        error = translateError,
                        onBack = { back() },
                        onStart = { req ->
                            scope.launch {
                                translatingArticle = true
                                translateError = null
                                translateProgress = null
                                // 记住是哪篇文章，用户离开后点悬浮条能回到这个页面
                                translateTargetId = req.articleId
                                val style = if (req.customStyle.isNotBlank()) req.customStyle
                                else when (req.styleIdx) {
                                    0 -> "忠实原文，结构语序尽量贴近"
                                    1 -> "通顺自然，像中文母语者写的"
                                    else -> "保留原文的语气、节奏和修辞"
                                }
                                val thinking = when (req.thinkingIdx) {
                                    0 -> null; 1 -> "low"; 2 -> "high"; else -> "max"
                                }
                                val translator = ArticleTranslator(
                                    apiKey = settings.apiKey,
                                    model = settings.model,
                                    cache = cacheRepo,
                                    onUsage = { u ->
                                        scope.launch { usageRepo.record("TRANS_ALL", settings.model, u) }
                                    }
                                )
                                val outcome = translator.run(
                                    paragraphs = paras,
                                    mode = req.mode,
                                    style = style,
                                    thinking = thinking,
                                    onProgress = { done, total ->
                                        translateProgress = done to total
                                    }
                                )
                                translatingArticle = false
                                translateProgress = null
                                translateTargetId = null
                                when (outcome) {
                                    is ArticleTranslator.Outcome.Ok -> {
                                        val saved = runCatching {
                                            repo.add(
                                                outcome.result.title,
                                                outcome.result.content,
                                                "translated"
                                            )
                                        }
                                        translateError = null
    // 和生成文章一致，还停在翻译页才跳过去
                                        // 已经离开的话只提示，不打断用户当前在做的事
                                        saved.getOrNull()?.let { a ->
                                            if (onPage("translate/{id}")) {
                                                replace(Route.Reader(a.id))
                                            } else {
    // 用户已经离开翻译页，只弹toast的话他不知道译文去哪了，留一个可点的入口
                                                // 他不知道译文去哪了，留一个可点的入口
                                                translatedReadyId = a.id
                                            }
                                            Notify.show("已生成《${a.title}》· 约 ${a.wordCount} 字")
                                        } ?: Notify.show("保存失败，存储空间可能不足")
                                    }
                                    is ArticleTranslator.Outcome.Fail -> {
                                        translateError = outcome.message
                                        // 不在翻译页时看不到行内错误，补一条全局提示
                                        if (!onPage("translate/{id}")) {
                                            Notify.show(outcome.message)
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            composable("font") { entry ->
                onPage("font")
                com.oracole.readit.ui.screen.FontScreen(
                    uiFont = appearance.uiFont,
                    readingFont = appearance.readingFont,
                    onPickUi = { appearance.updateFont(it) },
                    onPickReading = { appearance.updateReadingFont(it) },
                    // 导入或删除字体后，主题层要知道清单变了
                    onFontsChanged = { list -> appearance.refreshFonts(list) },
                    onBack = { back() }
                )
            }

            composable("ailog") { entry ->
                onPage("ailog")
                com.oracole.readit.ui.screen.AiLogScreen(onBack = { back() })
            }

            composable("appearance") { entry ->
                onPage("appearance")
                com.oracole.readit.ui.screen.AppearanceScreen(
                    appearance = appearance,
                    transitionStyle = settings.transitionStyle,
                    onTransitionChange = { settings.updateTransitionStyle(it) },
                    onBack = { back() }
                )
            }

            composable("reading") { entry ->
                ReadingSettingsScreen(
                    settings = readerSettings,
                    onBack = { back() }
                )
            }

            composable("ai") { entry ->
                AiSettingsScreen(
                    settings = settings,
                    onBack = { back() }
                )
            }

            composable(
                "reader/{id}",
                // 显式声明成Long，默认是String，getLong取不到值会返回0
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
            ) { entry ->
                val r = Route.Reader(entry.arguments?.getLong("id") ?: 0L, readerAnchor?.first, readerAnchor?.second)
                // 只在这里读，收藏别的文章不会让这个页面重组
                val articles by articlesState
                // 正文字体，包在remember里，不然每帧都会去查一次缓存
                val rf = appearance.readingFont
                val readingFamily = remember(rf) {
                    if (rf is com.oracole.readit.ui.theme.UiFont.System) null
                    else com.oracole.readit.ui.theme.UiFonts.familyOf(ctx, rf)
                }
                val live = articles.firstOrNull { it.id == r.articleId }
                if (live == null) {
                    LaunchedEffect(Unit) { back() }
                } else {
                    ReaderScreen(
                        article = live,
                        settings = readerSettings,
                        readingFamily = readingFamily,
                        dictRepo = dictRepo,
                        collectedState = collectedState,
                        apiKey = settings.apiKey,
                        model = settings.model,
                        onBack = { back() },
                        onToggleFavorite = { scope.launch { repo.toggleFavorite(live.id) } },
                        onCollect = { w, ph, tr, sen, form, aiBase ->
                            scope.launch {
                            // 只有开关打开、且AI给的原形确实不同才归约
                            // 相同就不必多写一条forms记录
                                val b = if (readerSettings.collectByBase) aiBase else null
                                collectNormalized(
                                    w, ph, tr, sen, live.id, live.title, form,
                                    base = b?.takeIf { !it.equals(w, ignoreCase = true) }
                                )
                            }
                        },
                        onUncollect = { w, base ->
                            scope.launch {
                                // 必须能按形态找到，收藏means时记录是mean，
                                // 只用word匹配会找不到，点了没反应
                                vocabRepo.findByWordOrForm(w, base)
                                    ?.let { vocabRepo.remove(it.id) }
                            }
                        },
                        onSaveProgress = { off -> scope.launch { repo.saveProgress(live.id, off) } },
                        anchorSentence = r.anchorSentence,
                        anchorWord = r.anchorWord,
                        onToggleTargets = { on ->
                            scope.launch { repo.setHighlightTargets(live.id, on) }
                        },
                        onTranslateArticle = { go(Route.Translate(live.id)) },
                        onAddContext = { word, sentence, form, base ->
                            scope.launch {
                                vocabRepo.addContext(
                                    word, sentence, live.id, live.title, form, base
                                )
                            }
                        },
                        onAddForm = { base, form ->
                            scope.launch { vocabRepo.addForm(base, form) }
                        },
                        usageRepo = usageRepo,
                        cacheRepo = cacheRepo
                    )
                }
            }

            composable(
                "vocab/{id}",
                // 显式声明成Long，默认是String，getLong取不到值会返回0
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
            ) { entry ->
                val r = Route.VocabDetail(entry.arguments?.getLong("id") ?: 0L)
                val vocabItems by vocabItemsState
                val live = vocabItems.firstOrNull { it.id == r.vocabId }
                if (live == null) {
                    LaunchedEffect(Unit) { back() }
                } else {
                    VocabDetailScreen(
                        item = live,
                        onBack = { back() },
                        onToggleKnown = { scope.launch { vocabRepo.setStatus(live.id, !live.isKnown) } },
                        onRemove = {
                            scope.launch {
                                vocabRepo.remove(live.id)
                                back()
                            }
                        },
                        onSpeak = { Tts.speak(live.displayWord, readerSettings.ttsRate) },
                        onOpenContext = { articleId, sentence, word ->
                            go(Route.Reader(articleId, sentence, word))
                        },
                        showForms = readerSettings.showWordForms,
                        aiReady = settings.apiKey.isNotBlank(),
                        loadForms = { w -> vocabRepo.loadForms(w) },
                        loadAsks = { w -> vocabRepo.loadAsks(w) },
                        addAsk = { w, q, a -> vocabRepo.addAsk(w, q, a) },
                        deleteAsk = { id -> vocabRepo.deleteAsk(id) },
                        fetchForms = { w ->
                            if (settings.apiKey.isBlank()) {
                                null
                            } else {
                                val r = DeepSeekClient.chat(
                                    apiKey = settings.apiKey,
                                    systemPrompt = Prompt.FORM_SYSTEM,
                                    userPrompt = Prompt.baseUser(w, ""),
                                    maxTokens = 600,
                                    model = settings.model,
                                    tag = "FORM"
                                )
                                if (r is AiResult.Ok) {
                                    usageRepo.record("FORM", settings.model, r.usage)
    // 原文落库，长期保存，和AI缓存分开，清缓存不丢
                                    vocabRepo.saveForms(w, r.content)
                                    com.oracole.readit.data.ai.parseForms(r.content)
                                } else null
                            }
                        },
                        onAsk = { key ->
                        // key是ROOT、COLLO、EXAMPLE、PHONETIC，或用户自由输入的问题
                        // 四项复用词卡那套提示词，自由输入走ASK_SYSTEM
                            val isTab = key in setOf("ROOT", "DERIVE", "COLLO", "EXAMPLE", "PHONETIC")
                        // 自由提问的tab带版本号，之前那版没把正在看哪个词传给模型
                            // 传给模型，缓存里存的是请提供具体单词这种废话回答
                            // 不换版本的话，改完提示词用户还是会读到旧答案
                            val cacheTab = if (isTab) "DETAIL_$key" else "ASK_V2"
                            val cacheKey = "${live.displayWord.lowercase()}·$key"
                            val hit = cacheRepo.get(cacheKey, "ask", cacheTab)

                            // 用已有的语境句当上下文（有的话），词根/搭配在有语境时更准
                            val ctx = live.contexts.firstOrNull()?.sentence ?: ""

    // 直接返回结构化对象，排版交给UI，
                            // 压平成字符串的话，词性/释义/例句只能共用一个字号和颜色
                            // 挤在一起看不清层次
                            suspend fun render(raw: String): Any? = when (key) {
                                "ROOT" -> com.oracole.readit.data.ai.parseRoot(raw)
                                "DERIVE" -> com.oracole.readit.data.ai.parseDerivations(raw)
                                "COLLO" -> com.oracole.readit.data.ai.parseCollocations(raw)
                                "EXAMPLE" -> com.oracole.readit.data.ai.parseExamples(raw)
                                "PHONETIC" -> com.oracole.readit.data.ai.parsePhonetic(raw)
                        // 自由提问走ASK_SYSTEM，输出answer和confidence
                        // 用parseAsk解析，之前这里还是搜索页的parseSearchAnswer
                        // 解析不出来就返回null，界面显示没有拿到回答
                                else -> com.oracole.readit.data.ai.parseAsk(raw)
                            }

                            val cached = hit?.let { render(it) }
                            if (cached != null) {
                                cached
                            } else if (settings.apiKey.isBlank()) {
                                Notify.show("还没有配置 DeepSeek 密钥")
                                null
                            } else {
                                val sys = when (key) {
                                    "ROOT" -> Prompt.ROOT_SYSTEM
                                    "DERIVE" -> Prompt.DERIVE_SYSTEM
                                    "COLLO" -> Prompt.COLLOCATION_SYSTEM
                                    "EXAMPLE" -> Prompt.EXAMPLE_SYSTEM
                                    "PHONETIC" -> Prompt.PHONETIC_SYSTEM
                        // 详情页的自由提问用ASK_SYSTEM，自由回答，
                        // 不是SEARCH_ASK_SYSTEM，那套输出词典条目，
                                    // 问给我一些搭配会回一份释义）
                                    else -> Prompt.ASK_SYSTEM
                                }
                                // 自由提问也要带上正在看哪个词和它的语境，
                                // 否则用户说这个词是什么意思，模型只看到这七个字
                        // 不知道指的是哪个词，四个预设chip能正常工作正是因为
                        // 它们走了baseUser(word, ctx)
                                val usr = if (isTab) Prompt.baseUser(live.displayWord, ctx)
                                else Prompt.detailAskUser(
                                    live.displayWord,
                                    live.translation,
                                    ctx,
                                    key
                                )
                                val r = DeepSeekClient.chat(
                                    apiKey = settings.apiKey,
                                    systemPrompt = sys,
                                    userPrompt = usr,
                                    maxTokens = if (isTab) 640 else 600,
                                    model = settings.model,
                                    tag = if (isTab) "DETAIL_$key" else "DETAIL_ASK"
                                )
                                when (r) {
                                    is AiResult.Ok -> {
                                        usageRepo.record(cacheTab, settings.model, r.usage)
                                        val text = render(r.content)
                                        if (text == null) {
                                            Notify.show("返回格式异常")
                                            null
                                        } else {
                                            cacheRepo.put(cacheKey, "ask", cacheTab, r.content, settings.model)
                                            text
                                        }
                                    }
                                    is AiResult.Fail -> {
                                        Notify.show("请求失败，请稍后再试")
                                        null
                                    }
                                }
                            }
                        }
                    )
                }
            }

            composable("main") { entry ->
                // 这三个只有主界面（书架/词汇/设置）用得到
                // 在destination内读值，阅读页、翻译页里发生的数据变化
                // 不会再让tab容器重组
                val articles by articlesState
                val vocabItems by vocabItemsState
                val collectedWords by collectedState
                val usage by usageState

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                // dock和多选操作条互斥，交叉切换而不是叠在一起
                        //
                // 两者高度不同，dock一条、操作条两行，所以不能用横向滑
                // 让旧的下沉、新的升起，配合淡入淡出，位置关系保持
                        // 底栏在这一层，只是内容换了
                        //
                // 用spring不用tween，和全局动效一致，位移用不过冲的
                        // 底栏贴屏幕底边，过冲会露出来一截空白
                        AnimatedContent(
                            targetState = vocabSelecting && index == 1,
                            transitionSpec = {
                                val sp = spring<androidx.compose.ui.unit.IntOffset>(
                                    dampingRatio = 1f, stiffness = 700f
                                )
                                val fade = spring<Float>(dampingRatio = 1f, stiffness = 900f)
                                (slideInVertically(sp) { h -> h } + fadeIn(fade))
                                    .togetherWith(
                                        slideOutVertically(sp) { h -> h } + fadeOut(fade)
                                    )
                            },
                            label = "bottomBar"
                        ) { selectionMode ->
                            if (selectionMode) {
                                VocabSelectionBar(
                                    filter = vocabFilter,
                                    selectedCount = vocabSelected.size,
                                    selectedIds = vocabSelected.toList(),
                                    onSetKnownBatch = { ids, known ->
                                        scope.launch { vocabRepo.setStatusBatch(ids, known) }
                                        if (known && vocabFilter == 1) {
                                            vocabSelecting = false; vocabSelected.clear()
                                        } else if (!known && vocabFilter == 2) {
                                            vocabSelecting = false; vocabSelected.clear()
                                        }
                                    },
                                    onRemoveBatch = { ids ->
                                        scope.launch { vocabRepo.removeBatch(ids) }
                                        vocabSelecting = false
                                        vocabSelected.clear()
                                    },
                                    onCreateArticle = {
                                        val picked = vocabItems.filter { vocabSelected.contains(it.id) }
                                        vocabSelecting = false
                                        go(Route.Generate(picked.map { it.word }))
                                    }
                                )
                            } else {
                                NavigationBar {
                                    TABS.forEachIndexed { i, tab ->
                                        NavigationBarItem(
                                            selected = index == i,
                                            onClick = {
                                                index = i
                                                // 切走时退出多选。否则切回词汇页会突然
                                                // 又回到多选状态，选择的词也还在，很突兀
                                                if (i != 1 && vocabSelecting) {
                                                    vocabSelecting = false
                                                    vocabSelected.clear()
                                                }
                                            },
                                            icon = { Icon(if (index == i) tab.filled else tab.outlined, tab.label) },
                                            label = { Text(tab.label) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { pad ->
    // 外层Scaffold只负责给底部导航栏让位，状态栏由各页面自己的
    // Scaffold加MediumTopAppBar处理，这里若padding会再让出一条状态栏
                    // 一条状态栏，导致大字标题收起后碰不到屏幕顶端
                    Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
    // 三个tab之间横向滑动，方向跟着切换方向走
                        //
    // 位移是宽度的三分之一，旧页退出一截、新页盖上来一截
    // 位移走spatial弹簧，落位时有一点点回弹，
                        // 这一下的作用是让切换活起来，纯匀速到位会显得死板
    // 透明度走effects，不过冲，否则会闪成负值
                        AnimatedContent(
                            targetState = index,
                            transitionSpec = {
                                val dir = if (targetState > initialState) 1 else -1
                                val sp = Motion.spatialNoBounce<androidx.compose.ui.unit.IntOffset>()
                                val fade = Motion.effects<Float>()
                                (slideInHorizontally(sp) { w -> dir * w / 3 } + fadeIn(fade))
                                    .togetherWith(
                                        slideOutHorizontally(sp) { w -> -dir * w / 3 } + fadeOut(fade)
                                    )
                            },
                            label = "tab"
                        ) { tab -> when (tab) {
                            0 -> ShelfScreen(
                                articles = articles,
                                learningCount = vocabItems.count { !it.isKnown },
                                knownCount = vocabItems.count { it.isKnown },
                                onImportClick = { showImportSheet = true },
                                onPaste = { go(Route.Paste) },
                                onOpen = { go(Route.Reader(it.id)) },
                                onToggleFavorite = { scope.launch { repo.toggleFavorite(it.id) } },
                                onRename = { a, t -> scope.launch { repo.rename(a.id, t) } },
                                onDelete = { a -> scope.launch { repo.delete(a.id) } },
                                onFavoriteBatch = { ids, fav ->
                                    scope.launch { repo.setFavoriteBatch(ids, fav) }
                                },
                                onDeleteBatch = { ids -> scope.launch { repo.deleteBatch(ids) } }
                            )

                            1 -> VocabScreen(
                                items = vocabItems,
                                onOpenDetail = { go(Route.VocabDetail(it.id)) },
                                onSetKnown = { v, k -> scope.launch { vocabRepo.setStatus(v.id, k) } },
                                onRemove = { v -> scope.launch { vocabRepo.remove(v.id) } },
                                onRemoveBatch = { ids -> scope.launch { vocabRepo.removeBatch(ids) } },
                                onSetKnownBatch = { ids, k -> scope.launch { vocabRepo.setStatusBatch(ids, k) } },
                                onCreateArticle = { list ->
                                    go(Route.Generate(list.map { it.displayWord }))
                                },
                                onLookup = { q ->
    // 词典完全匹配，忽略大小写
    // 不走lookup的四级降级，搜索要的是准确命中
                                    // 而不是"猜一个最接近的"
                                    dictRepo.exactTranslation(q)
                                },
                                collectedWords = collectedWords,
                                selecting = vocabSelecting,
                                onSelectingChange = {
                                    vocabSelecting = it
                                    if (!it) vocabSelected.clear()
                                },
                                selected = vocabSelected,
                                onFilterChange = { vocabFilter = it },
                                onCollect = { word, translation ->
                                    scope.launch {
    // 查词页收藏没有出处句子，articleId和articleTitle传null
                                        //
    // 释义优先级是词典优于AI
    // 之前AI结果区直接传AI的释义进来，没回查词典，
    // 词典里其实有这个词，却存了AI的说法
                                        // 所以这里统一再查一次词典，查得到就用词典的
                                        val dictTr = dictRepo.exactTranslation(word)
                                            ?.takeIf { it.isNotBlank() }
                                        val finalTr = dictTr ?: translation
                                        if (word.lowercase() !in collectedWords) {
    // 查词页没有语境，没有句子，AI判定不了这句里是哪个词
    // 所以这里不传base，按用户输入的形态存
    // 这和按原形收藏不冲突，那个开关只对文章里有上下文的收藏生效
                                            collectNormalized(
                                                word, null, finalTr, "",
                                                null, null, word,
                                                base = null
                                            )
                                            Notify.show("已收藏 $word")
                                        } else {
    // 必须按形态找，收藏means时记录是mean，
    // 只用word匹配会找不到，点了没反应却照样弹提示
                                            vocabRepo.findByWordOrForm(word)
                                                ?.let { vocabRepo.remove(it.id) }
                                            Notify.show("已取消收藏")
                                        }
                                    }
                                },
                                onAiAsk = { question ->
                                    val key = "search·$question"
                                    val hit = cacheRepo.get(key, "search", "ASK")
                                    val cached = hit?.let {
                                        com.oracole.readit.data.ai.parseSearchAnswer(it)
                                    }
                                    if (cached != null) {
                                        cached
                                    } else if (settings.apiKey.isBlank()) {
                                        Notify.show("还没有配置 DeepSeek 密钥")
                                        null
                                    } else {
                                        val r = DeepSeekClient.chat(
                                            apiKey = settings.apiKey,
                                            systemPrompt = Prompt.SEARCH_ASK_SYSTEM,
                                            userPrompt = question,
                                            maxTokens = 900,
                                            model = settings.model,
                                            tag = "SEARCH_ASK"
                                        )
                                        when (r) {
                                            is AiResult.Ok -> {
                                                usageRepo.record("ASK", settings.model, r.usage)
                                                val parsed =
                                                    com.oracole.readit.data.ai.parseSearchAnswer(r.content)
                                                if (parsed != null) {
                                                    cacheRepo.put(key, "search", "ASK", r.content, settings.model)
                                                    parsed
                                                } else {
                                                    Notify.show("返回格式异常")
                                                    null
                                                }
                                            }
                                            is AiResult.Fail -> {
                                                Notify.show("请求失败，请稍后再试")
                                                null
                                            }
                                        }
                                    }
                                }
                            )

                            else -> SettingsScreen(
                                readerSettings = readerSettings,
                                apiConfigured = settings.hasKey,
                                serviceLabel = settings.serviceName,
                                dictInfo = when (val d = dictState) {
                                    is DictState.Ready -> "${d.wordCount / 10000} 万词条 · 已导入"
                                    is DictState.Corrupt -> "文件异常"
                                    DictState.NotImported -> "未导入"
                                },
                                articleCount = articles.size,
                                vocabCount = vocabItems.size,
                                onOpenAi = { go(Route.AiSettings) },
                                onOpenDict = { go(Route.DictSettings) },
                                onOpenReading = { go(Route.ReadingSettings) },
                                onOpenUsage = { go(Route.Usage) },
                                onOpenAbout = { go(Route.About) },
                                onOpenVocabImport = { go(Route.VocabImport) },
                                usageSummary = usage,
                                transitionStyle = settings.transitionStyle,
                                onTransitionChange = { settings.updateTransitionStyle(it) },
                                appearanceMode = appearance.mode,
                                seedLabel = com.oracole.readit.ui.screen.seedNameOf(appearance.seed),
                                onOpenAppearance = { go(Route.Appearance) },
                                onOpenAiLog = { go(Route.AiLog) },
                                fontLabel = appearance.uiFont.label,
                                onOpenFont = { go(Route.Font) }
                            )
                        }
                        }

                    }
                }
            }
            }

        // 后台任务悬浮条，生成文章和全文翻译共用同一个位置
        // 同时只可能有一个在跑，都在用AI，用户不会并发，所以直接叠放
        //
        // 它在route分支外面，拿不到Scaffold给的底部让位，所以自己留一段
        //
        // 间距对所有页面用同一个值，之前按页面算，书架152、阅读页76
        // 结果是同一个浮条在书架高、进阅读页变低、退回来又变高，看着像在跳
        // 浮条只需要避开屏幕底部这一段，页面里临时弹出的多选栏由那页自己处理
        //
        // 只在书架和阅读页显示。别的页面底部有自己的操作条
        // （粘贴页的取消/导入），浮条居中会横穿它们
        val pillBottom = when (route) {
            Route.Main, is Route.Reader -> 80.dp
            else -> null
        }
        if (pillBottom != null) {
        // 用Column而不是叠在一起的Box，几条同时可见时纵向排开
            // 否则后画的完全盖住先画的（译文已生成 + 正在翻译另一篇）
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = pillBottom),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                TaskPill(
                    visible = generating,
                    title = "正在生成文章"
                ) {
                    genTask?.let { go(Route.Generate(it.words)) }
                }
                TaskPill(
                    visible = translatingArticle,
                    title = "正在翻译全文",
                    subtitle = translateProgress?.let {
        // done加1才是正在跑第几批
                        "第 ${(it.first + 1).coerceAtMost(it.second)}/${it.second} 批"
                    } ?: "点击查看进度"
                ) {
                    translateTargetId?.let { go(Route.Translate(it)) }
                }
                TaskPill(
                    visible = translatedReadyId != null,
                    title = "译文已生成",
                    subtitle = "点击查看",
                    // 已经完成了，不能转圈，转圈的意思是还在跑
                    showProgress = false
                ) {
                    translatedReadyId?.let {
                        translatedReadyId = null
                        go(Route.Reader(it))
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ImportSheet(
            onDismiss = { showImportSheet = false },
            onPickFile = {
                showImportSheet = false
                pickFile.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
            },
            onPaste = { showImportSheet = false; go(Route.Paste) },
        )
    }
}
