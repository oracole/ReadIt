package com.oracole.readit.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 系统 TTS
 *
 * 为什么做成单例：
 * TextToSpeech 的构造是异步的，要连引擎、加载语音数据，通常 200–800ms
 * 点击时才 new 的话，第一次必然没声音。所以在 Application/Activity 启动时预热
 */
object Tts {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var initFailed = false
    /** 英文语音包是否可用 */
    private var englishOk = false
    /** 初始化完成前请求朗读的文本，就绪后补发一次 */
    private var pending: String? = null

    fun init(ctx: Context) {
        if (engine != null || initFailed) return
        val app = ctx.applicationContext
        engine = TextToSpeech(app) { status ->
            if (status != TextToSpeech.SUCCESS) {
                initFailed = true
                Notify.show("语音引擎初始化失败")
                return@TextToSpeech
            }
            val e = engine ?: return@TextToSpeech
            val r = runCatching { e.setLanguage(Locale.US) }.getOrDefault(-1)
            englishOk = r != TextToSpeech.LANG_MISSING_DATA &&
                r != TextToSpeech.LANG_NOT_SUPPORTED
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
            ready = true
            // 初始化期间用户已经点了，补发
            pending?.let { speakNow(it) }
            pending = null
        }
    }

    /**
     * 朗读一段文本。单词、词组、整句都走这里
     * 音标不要传进来，TTS 会把 /.../ 念成乱码
     *
     * 语速用 1.0：快放会破坏连读和弱读，听起来像正常说话被加速
     * 而语言学习的听感恰恰依赖那些被压扁的音节
     */
    fun speak(text: String, rate: Float = 1.0f) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!ready) {
            // 还没就绪：记下来，就绪后补发；同时确保 init 被调用过
            pending = t
            if (initFailed) Notify.show("设备没有可用的语音引擎")
            return
        }
        if (!englishOk) {
            Notify.show("系统缺少英文语音包，请到系统设置中下载")
            return
        }
        speakNow(t, rate)
    }

    private fun speakNow(text: String, rate: Float = 1.0f) {
        val e = engine ?: return
        runCatching {
            e.setSpeechRate(rate)
            e.setPitch(1.0f)
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "readit_${System.nanoTime()}")
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun shutdown() {
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}
