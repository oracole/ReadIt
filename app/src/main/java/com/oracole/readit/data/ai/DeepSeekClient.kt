package com.oracole.readit.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLException

/** 调用结果 */
sealed interface AiResult {
    data class Ok(val content: String, val usage: Usage) : AiResult
    data class Fail(val kind: ErrorKind, val detail: String = "") : AiResult
}

enum class ErrorKind {
    NO_KEY, INVALID_KEY, INSUFFICIENT_BALANCE, RATE_LIMIT,
    NETWORK, TIMEOUT, EMPTY_CONTENT, TRUNCATED, PARSE_ERROR, UNKNOWN
}

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int
) {
    /** 按 deepseek-flash 高峰价估算，单位：微元（1e-6 元） */
    fun costMicro(): Long {
        val inHit = cacheHitTokens * 0.04  / 1_000_000.0 * 1_000_000
        val inMiss = cacheMissTokens * 2.0 / 1_000_000.0 * 1_000_000
        val out = completionTokens * 8.0 / 1_000_000.0 * 1_000_000
        return (inHit + inMiss + out).toLong()
    }
}

object DeepSeekClient {

    // 当前请求的完整地址，由 ReadItApp 在组合时同步过来
    // 放全局而不是加参数，因为有九个调用点
    // 每个都多传一个地址没有意义，一次运行里它们必然用同一份配置
    // Volatile，改设置在主线程，读在 IO 线程
    @Volatile
    var endpoint: String = "https://api.deepseek.com/chat/completions"

    // 发一次 chat completion 请求
    // timeoutMs 是连接与读取超时
    suspend fun chat(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 512,
        model: String = "deepseek-flash",
        timeoutMs: Int = 30_000,
        /** 思考强度：null = 关闭思考（默认，最省）；"low" / "high" / "max" = 开启 */
        thinkingLevel: String? = null,
        /** 用途标签，只用于日志 */
        tag: String = ""
    ): AiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext AiResult.Fail(ErrorKind.NO_KEY)

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
            if (thinkingLevel == null) {
                put("thinking", JSONObject().put("type", "disabled"))
            } else {
                put("thinking", JSONObject().put("type", "enabled"))
                put("reasoning_effort", thinkingLevel)
            }
            put("response_format", JSONObject().put("type", "json_object"))
            put("max_tokens", maxTokens)
            put("stream", false)
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { r: BufferedReader -> r.readText() } ?: ""

            if (tag.isNotEmpty()) {
                com.oracole.readit.util.AiLog.record(
                    tag, systemPrompt, userPrompt,
                    if (code == 200) text else "HTTP $code: ${text.take(300)}"
                )
            }

            when {
                code == 200 -> parseSuccess(text)
                code == 401 -> AiResult.Fail(ErrorKind.INVALID_KEY)
                code == 402 -> AiResult.Fail(ErrorKind.INSUFFICIENT_BALANCE)
                code == 429 -> AiResult.Fail(ErrorKind.RATE_LIMIT)
                code in 500..599 -> AiResult.Fail(ErrorKind.UNKNOWN, "服务器 $code")
                else -> AiResult.Fail(ErrorKind.UNKNOWN, "$code ${text.take(120)}")
            }
        } catch (e: java.net.SocketTimeoutException) {
            AiResult.Fail(ErrorKind.TIMEOUT)
        } catch (e: SSLException) {
            AiResult.Fail(ErrorKind.NETWORK)
        } catch (e: java.io.IOException) {
            AiResult.Fail(ErrorKind.NETWORK)
        } catch (e: Exception) {
            AiResult.Fail(ErrorKind.UNKNOWN, e.message ?: "")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseSuccess(text: String): AiResult = runCatching {
        val root = JSONObject(text)
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val finish = choice.optString("finish_reason", "")
        val content = choice.getJSONObject("message").optString("content", "")
        if (content.isBlank()) {
            // 被截断时内容可能是空的（思考链把预算吃光了）
            return if (finish == "length") AiResult.Fail(ErrorKind.TRUNCATED)
            else AiResult.Fail(ErrorKind.EMPTY_CONTENT)
        }
        // 有内容但 finish=length：要看内容本身完不完整
        //   JSON 能解析 → 思考链撑满上限但正文完整，可用
        //   JSON 解析不了 → 正文也被截断，必须报 TRUNCATED
        // 不区分的话，调用方只会收到格式异常，看不出真实原因
        if (finish == "length") {
            val complete = runCatching { org.json.JSONObject(stripFence(content)) }.isSuccess
            if (!complete) return AiResult.Fail(ErrorKind.TRUNCATED)
        }

        val u = root.optJSONObject("usage")
        val usage = Usage(
            promptTokens = u?.optInt("prompt_tokens") ?: 0,
            completionTokens = u?.optInt("completion_tokens") ?: 0,
            cacheHitTokens = u?.optInt("prompt_cache_hit_tokens") ?: 0,
            cacheMissTokens = u?.optInt("prompt_cache_miss_tokens") ?: 0
        )
        AiResult.Ok(content, usage)
    }.getOrElse { AiResult.Fail(ErrorKind.PARSE_ERROR) }

    /** 测试连接：发一个极短的请求，验证 Key 与网络 */
    suspend fun testKey(apiKey: String): AiResult = chat(
        apiKey = apiKey,
        systemPrompt = "你是助手。输出 json。",
        userPrompt = "返回 json：{\"ok\":true}",
        maxTokens = 32,
        timeoutMs = 20_000
    )
}
