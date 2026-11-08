package com.oracole.readit.data.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 一条 AI 调用记录 */
data class UsageRecord(
    val id: Long,
    val at: Long,
    val tab: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int
) {
    val totalTokens: Int get() = promptTokens + completionTokens
    /** 微元 */
    val costMicro: Long
        get() {
            val hit = cacheHitTokens * 0.04
            val miss = cacheMissTokens * 2.0
            val out = completionTokens * 8.0
            return (hit + miss + out).toLong()
        }
}

/** 按功能汇总 */
data class TabUsage(val tab: String, val count: Int, val tokens: Int, val costMicro: Long)

/** 整体汇总 */
data class UsageSummary(
    val count: Int = 0,
    val tokens: Int = 0,
    /** 输入 token。数据库里本来就有这一列，之前只是没汇总到 Summary 上 */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costMicro: Long = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val byTab: List<TabUsage> = emptyList(),
    val recent: List<UsageRecord> = emptyList()
) {
    val costYuan: Double get() = costMicro / 1_000_000.0
    /** 缓存命中率 0..1 */
    val cacheRate: Float
        get() {
            val total = cacheHitTokens + cacheMissTokens
            return if (total <= 0) 0f else cacheHitTokens.toFloat() / total
        }
}

/**
 * AI 用量记录。独立 SQLite 文件，不碰 vocab.db / dict.db
 */
class AiUsageRepository(private val ctx: Context) {

    private val helper = object : SQLiteOpenHelper(ctx, "ai_usage.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE usage_log(
                     id                INTEGER PRIMARY KEY AUTOINCREMENT,
                     at                INTEGER NOT NULL,
                     tab               TEXT NOT NULL,
                     model             TEXT NOT NULL,
                     prompt_tokens     INTEGER NOT NULL DEFAULT 0,
                     completion_tokens INTEGER NOT NULL DEFAULT 0,
                     cache_hit         INTEGER NOT NULL DEFAULT 0,
                     cache_miss        INTEGER NOT NULL DEFAULT 0
                   )"""
            )
            db.execSQL("CREATE INDEX idx_usage_at ON usage_log(at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    private val _summary = MutableStateFlow(UsageSummary())
    val summary: StateFlow<UsageSummary> = _summary.asStateFlow()

    /** 记一笔 */
    suspend fun record(tab: String, model: String, u: Usage) = withContext(Dispatchers.IO) {
        runCatching {
            helper.writableDatabase.insert("usage_log", null, ContentValues().apply {
                put("at", System.currentTimeMillis())
                put("tab", tab)
                put("model", model)
                put("prompt_tokens", u.promptTokens)
                put("completion_tokens", u.completionTokens)
                put("cache_hit", u.cacheHitTokens)
                put("cache_miss", u.cacheMissTokens)
            })
        }
        refresh()
    }

    /** 重新算一遍汇总 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val since = monthStart()
        val all = ArrayList<UsageRecord>()
        runCatching {
            helper.readableDatabase.rawQuery(
                """SELECT id,at,tab,model,prompt_tokens,completion_tokens,cache_hit,cache_miss
                   FROM usage_log WHERE at >= ? ORDER BY at DESC""",
                arrayOf(since.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    all.add(
                        UsageRecord(
                            id = c.getLong(0), at = c.getLong(1),
                            tab = c.getString(2), model = c.getString(3),
                            promptTokens = c.getInt(4), completionTokens = c.getInt(5),
                            cacheHitTokens = c.getInt(6), cacheMissTokens = c.getInt(7)
                        )
                    )
                }
            }
        }
        val byTab = all.groupBy { it.tab }.map { (t, list) ->
            TabUsage(t, list.size, list.sumOf { it.totalTokens }, list.sumOf { it.costMicro })
        }.sortedByDescending { it.count }
        _summary.value = UsageSummary(
            count = all.size,
            tokens = all.sumOf { it.totalTokens },
            promptTokens = all.sumOf { it.promptTokens },
            completionTokens = all.sumOf { it.completionTokens },
            costMicro = all.sumOf { it.costMicro },
            cacheHitTokens = all.sumOf { it.cacheHitTokens },
            cacheMissTokens = all.sumOf { it.cacheMissTokens },
            byTab = byTab,
            recent = all.take(20)
        )
    }

    /** 清空（只删本月，保留历史？自用工具，直接全清） */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { helper.writableDatabase.delete("usage_log", null, null) }
        refresh()
    }

    private fun monthStart(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
