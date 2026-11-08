package com.oracole.readit.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 设置存储
// 第一版用 SharedPreferences（应用私有目录），后面要加备份导出时再迁
class Settings(ctx: Context) {

    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey by mutableStateOf(sp.getString(KEY_API, "") ?: "")
        private set

    var model by mutableStateOf(sp.getString(KEY_MODEL, DEFAULTS.model) ?: DEFAULTS.model)
        private set

    // 服务名称，只是显示用
    // 设置页那一行显示成 已配置 · DeepSeek
    // 换服务时能一眼看出现在连的是哪个
    var serviceName by mutableStateOf(sp.getString(KEY_NAME, DEFAULTS.name) ?: DEFAULTS.name)
        private set

    // 接口地址和路径分两个字段
    // 不合并成整条 URL，OpenAI 的 /v1 在地址里、路径还是 /chat/completions
    // 合起来用户得自己拆，分开填两边都照抄文档就行
    var baseUrl by mutableStateOf(sp.getString(KEY_BASE, DEFAULTS.baseUrl) ?: DEFAULTS.baseUrl)
        private set

    var apiPath by mutableStateOf(sp.getString(KEY_PATH, DEFAULTS.path) ?: DEFAULTS.path)
        private set

    fun updateApiKey(v: String) {
        apiKey = v.trim()
        sp.edit().putString(KEY_API, apiKey).apply()
    }

    fun updateModel(v: String) {
        model = v.trim().ifBlank { DEFAULTS.model }
        sp.edit().putString(KEY_MODEL, model).apply()
    }

    fun updateServiceName(v: String) {
        serviceName = v.trim().ifBlank { DEFAULTS.name }
        sp.edit().putString(KEY_NAME, serviceName).apply()
    }

    fun updateBaseUrl(v: String) {
        baseUrl = v.trim().trimEnd('/').ifBlank { DEFAULTS.baseUrl }
        sp.edit().putString(KEY_BASE, baseUrl).apply()
    }

    fun updateApiPath(v: String) {
        val p = v.trim().ifBlank { DEFAULTS.path }
        // 补前导斜杠，用户照文档抄通常带，自己敲容易漏
        apiPath = if (p.startsWith("/")) p else "/" + p
        sp.edit().putString(KEY_PATH, apiPath).apply()
    }

    // 恢复默认值
    // 只动名称、地址、路径、模型四项，不碰密钥
    // 密钥是用户自己去申请的，清掉要他重新去平台找
    fun resetToDefaults() {
        updateServiceName(DEFAULTS.name)
        updateBaseUrl(DEFAULTS.baseUrl)
        updateApiPath(DEFAULTS.path)
        updateModel(DEFAULTS.model)
    }

    /** 是不是全用默认值，界面上用来决定重置按钮要不要灰掉 */
    val isDefault: Boolean
        get() = serviceName == DEFAULTS.name && baseUrl == DEFAULTS.baseUrl &&
            apiPath == DEFAULTS.path && model == DEFAULTS.model

    /** 页面转场风格。切换后立刻生效，不用重启 */
    var transitionStyle by mutableStateOf(
        TransitionStyle.entries.getOrNull(sp.getInt(KEY_TRANSITION, -1)) ?: TransitionStyle.SLIDE
    )
        private set

    fun updateTransitionStyle(v: TransitionStyle) {
        transitionStyle = v
        sp.edit().putInt(KEY_TRANSITION, v.ordinal).apply()
    }

    val hasKey: Boolean get() = apiKey.isNotBlank()

    /** 打码显示：sk-ab…3f2a */
    fun maskedKey(): String {
        val k = apiKey
        if (k.isBlank()) return "未配置"
        return if (k.length <= 10) "已配置"
        else "已配置 · ${k.take(5)}…${k.takeLast(4)}"
    }

    // 出厂默认值
    // 只放 DeepSeek 一套，不做预设列表
    // 换服务时手填四项，比维护一张永远跟不上的预设表可靠
    object DEFAULTS {
        const val name = "DeepSeek"
        const val baseUrl = "https://api.deepseek.com"
        const val path = "/chat/completions"
        const val model = "deepseek-flash"
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_NAME = "service_name"
        const val KEY_BASE = "base_url"
        const val KEY_PATH = "api_path"
        const val KEY_TRANSITION = "transition_style"
    }
}
