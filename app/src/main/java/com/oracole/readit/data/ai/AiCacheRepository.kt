package com.oracole.readit.data.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * AI 查询结果的本地缓存
 *
 * 独立 SQLite 文件，不碰 vocab.db / dict.db（加表要写迁移，独立文件零风险）
 *
 * 键 = (word, sentence_hash, tab)。同一个词在不同句子里语境义不同，句子必须进键
 * 不按 model 区分：换模型后旧缓存仍可用，避免整库作废
 */
class AiCacheRepository(private val ctx: Context) {

    private val helper: SQLiteOpenHelper = object : SQLiteOpenHelper(ctx, "ai_cache.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE ai_cache(" +
                    "word TEXT NOT NULL," +
                    "sentence_hash TEXT NOT NULL," +
                    "tab TEXT NOT NULL," +
                    "payload TEXT NOT NULL," +
                    "sentence TEXT," +
                    "model TEXT," +
                    "created_at INTEGER NOT NULL," +
                    "PRIMARY KEY(word, sentence_hash, tab)) WITHOUT ROWID"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    /** 读缓存 */
    suspend fun get(word: String, sentence: String, tab: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                helper.readableDatabase.rawQuery(
                    "SELECT payload FROM ai_cache WHERE word=? AND sentence_hash=? AND tab=?",
                    arrayOf(word.lowercase(), hash(sentence), tab)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }

    /** 写缓存 */
    suspend fun put(
        word: String,
        sentence: String,
        tab: String,
        payload: String,
        model: String = ""
    ) =
        withContext(Dispatchers.IO) {
            runCatching {
                helper.writableDatabase.insertWithOnConflict(
                    "ai_cache", null,
                    ContentValues().apply {
                        put("word", word.lowercase())
                        put("sentence_hash", hash(sentence))
                        put("tab", tab)
                        put("payload", payload)
                        put("sentence", sentence.take(200))
                        put("model", model)
                        put("created_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            Unit
        }

    /** 只按词取最近一次（详情页用） */
    suspend fun getLatestByWord(word: String, tab: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                helper.readableDatabase.rawQuery(
                    "SELECT payload FROM ai_cache WHERE word=? AND tab=? ORDER BY created_at DESC LIMIT 1",
                    arrayOf(word.lowercase(), tab)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }

    /** 取「词 + 句子」下所有 tab 的结果，收藏时填 sense_extra */
    suspend fun getAllForSentence(word: String, sentence: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, String>()
            runCatching {
                helper.readableDatabase.rawQuery(
                    "SELECT tab,payload FROM ai_cache WHERE word=? AND sentence_hash=?",
                    arrayOf(word.lowercase(), hash(sentence))
                ).use { c ->
                    while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
                }
            }
            out
        }

    private fun hash(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val d = md.digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
