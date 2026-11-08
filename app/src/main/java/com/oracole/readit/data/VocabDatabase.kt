package com.oracole.readit.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 词汇库
 *
 * 单独一个 SQLite 文件，不和用户导入的词典混在一起，
 * 换词典时不能把收藏弄丢
 */
class VocabDatabase(ctx: Context) : SQLiteOpenHelper(ctx, "vocab.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE vocab (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                word          TEXT NOT NULL UNIQUE COLLATE NOCASE,
                display_word  TEXT NOT NULL,
                phonetic      TEXT,
                translation   TEXT NOT NULL,
                status        INTEGER NOT NULL DEFAULT 0,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL,
                known_at      INTEGER,
                -- 见过的形态，逗号分隔（went,goes,going）。收藏时按原形归一，
                -- 这里记下用户实际点过哪些形态，详情页显示「已收藏：…」
                forms         TEXT
            )
        """)
        db.execSQL("CREATE INDEX idx_vocab_status ON vocab(status, updated_at DESC)")

        db.execSQL("""
            CREATE TABLE vocab_context (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                vocab_id      INTEGER NOT NULL REFERENCES vocab(id) ON DELETE CASCADE,
                article_id    INTEGER,
                article_title TEXT,
                sentence      TEXT NOT NULL,
                word_form     TEXT,
                created_at    INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_ctx_vocab ON vocab_context(vocab_id, created_at DESC)")
        createExtraTables(db)
    }

    /**
     * 词形 + 问 AI 历史。两张表只在 v3 之后才有，所以单独提取一个方法
     * onCreate 和 onUpgrade 都调用，避免两处写重复的 DDL
     */
    private fun createExtraTables(db: SQLiteDatabase) {
        // 词形：一个词一条，长期保存。AI 缓存会清空，这张表不该跟着丢
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vocab_form (
                word       TEXT PRIMARY KEY COLLATE NOCASE,
                payload    TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)

        // 问 AI 历史：按词分组，一条问答一行
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ai_ask (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                word       TEXT NOT NULL COLLATE NOCASE,
                question   TEXT NOT NULL,
                answer     TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ask_word ON ai_ask(word, created_at DESC)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 -> v2：加 forms 列。老数据留空，下次收藏时补
        if (old < 2) {
            db.execSQL("ALTER TABLE vocab ADD COLUMN forms TEXT")
        }
        // v2 -> v3：加 vocab_form 和 ai_ask 两张表
        // 新表用 CREATE TABLE IF NOT EXISTS，老用户升级时不会因为表已存在而失败
        if (old < 3) {
            createExtraTables(db)
        }
    }
}
