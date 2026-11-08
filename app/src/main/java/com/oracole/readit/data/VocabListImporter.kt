package com.oracole.readit.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 词汇表里的一条：单词 + 可选的释义（文件里可能没有） */
data class VocabEntry(val word: String, val meaning: String)

/** 解析结果。encoding 和 delimiter 只用于「复制报告」，方便核对文件 */
sealed interface VocabParseResult {
    data class Ok(
        val entries: List<VocabEntry>,
        val fileName: String,
        val encoding: String,
        val delimiter: String,
        val badLines: List<String>
    ) : VocabParseResult

    data class Err(val message: String) : VocabParseResult
}

/**
 * 词汇表文件解析
 *
 * 支持纯文本、CSV、TSV、Anki 导出的 txt
 * 分隔符自动判定：有 Anki 的 #separator 头就用它
 * 否则统计前 20 个有效行里 tab / 逗号 / 分号的个数，取最多的那个
 *
 * 只负责切成一条条，不查词典、不写库
 */
object VocabListImporter {

    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_LINES = 20_000

    suspend fun read(ctx: Context, uri: Uri): VocabParseResult = withContext(Dispatchers.IO) {
        val name = queryName(ctx, uri) ?: "未命名文件"

        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext VocabParseResult.Err("打不开这个文件，可能已损坏")
        } catch (e: Exception) {
            return@withContext VocabParseResult.Err("打不开这个文件，可能已损坏")
        }

        if (bytes.isEmpty()) return@withContext VocabParseResult.Err("这个文件是空的")
        if (bytes.size > MAX_BYTES) {
            return@withContext VocabParseResult.Err("文件超过 5 MB，建议拆分后分批导入")
        }

        val decoded = decode(bytes)
            ?: return@withContext VocabParseResult.Err("无法识别文件编码，请另存为 UTF-8 后重试")

        parse(decoded.text, name, decoded.encoding)
    }

    /** 粘贴进来的文本。没有文件名，忽略编码（字符串已在内存里） */
    fun parseText(text: String): VocabParseResult = parse(text, PASTED_LABEL, "")

    const val PASTED_LABEL = "粘贴的文本"

    /** 仅供单元测试：走「字节 → 解码 → 解析」全链路 */
    internal fun parseBytesForTest(bytes: ByteArray, fileName: String): VocabParseResult {
        val d = decode(bytes) ?: return VocabParseResult.Err("编码无法识别")
        return parse(d.text, fileName, d.encoding)
    }

    /** 仅供单元测试：跳过文件读取，直接喂字符串 */
    internal fun parseForTest(text: String, fileName: String, encoding: String) =
        parse(text, fileName, encoding)

    private fun parse(text: String, fileName: String, encoding: String): VocabParseResult {
        // decode() 已按字节剥过 BOM，这里是字符串层兜底：
        // 若上游传来带 U+FEFF 的字符串，首列表头会变成 "\uFEFFword"，表头识别就会失效
        val clean = text.removePrefix("\uFEFF")
        val rawLines = clean.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (rawLines.size > MAX_LINES) {
            return VocabParseResult.Err("文件有 ${rawLines.size} 行，超过上限 $MAX_LINES 行")
        }

        // Anki 导出头： #separator:tab 之类
        var declared: Char? = null
        val body = ArrayList<String>(rawLines.size)
        for (line in rawLines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#")) {
                if (declared == null) {
                    val lower = t.lowercase()
                    if (lower.startsWith("#separator:")) {
                        declared = when {
                            lower.contains("tab") -> '\t'
                            lower.contains("comma") -> ','
                            lower.contains("semicolon") -> ';'
                            lower.contains("pipe") -> '|'
                            else -> null
                        }
                    }
                }
                continue
            }
            body.add(line)
        }

        if (body.isEmpty()) return VocabParseResult.Err("这个文件没有可读的行")

        val sep = declared ?: detectSeparator(body)
        val entries = ArrayList<VocabEntry>()
        val seen = HashSet<String>()
        val bad = ArrayList<String>()

        // Excel / Numbers 另存 CSV 时几乎总会带表头
        // 不识别的话 word,meaning,note 整行会被当成一个词导进去
        val dataLines = if (looksLikeHeader(body.firstOrNull(), sep)) body.drop(1) else body

        for (line in dataLines) {
            val cols = splitCsv(line, sep)
            val word = cols.firstOrNull()?.trim()?.trim('"')?.trim().orEmpty()
            val meaning = if (cols.size > 1) {
                cols.drop(1).map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                    .joinToString("；")
            } else ""

            val reason = invalidReason(word)
            if (reason != null) {
                if (bad.size < 50) bad.add("$line （$reason）")
                continue
            }
            if (!seen.add(word.lowercase())) continue
            entries.add(VocabEntry(word, meaning))
        }

        if (entries.isEmpty()) {
            return VocabParseResult.Err("没有识别到有效的词条，请检查文件格式")
        }

        return VocabParseResult.Ok(
            entries = entries,
            fileName = fileName,
            encoding = encoding,
            delimiter = when (sep) {
                '\t' -> "制表符"
                ',' -> "逗号"
                ';' -> "分号"
                '|' -> "竖线"
                else -> "整行"
            },
            badLines = bad
        )
    }

    private fun detectSeparator(lines: List<String>): Char {
        val sample = lines.take(20)
        var best = '\u0000'
        var bestCount = 0
        for (c in charArrayOf('\t', ',', ';')) {
            val n = sample.count { it.contains(c) }
            if (n > bestCount) { bestCount = n; best = c }
        }
        return best
    }

    /**
     * 按分隔符切分，但引号内的分隔符不算
     * Excel 导出 "SENTENCE, WITH COMMA" 就是这种形状
     * 直接 split 会把一个字段切成两半
     */
    private fun splitCsv(line: String, sep: Char): List<String> {
        if (sep == '\u0000') return listOf(line)
        val out = ArrayList<String>(4)
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuote && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i++
                    } else inQuote = !inQuote
                }
                c == sep && !inQuote -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }

    /** 首行首列是常见表头词就认为是表头 */
    private fun looksLikeHeader(line: String?, sep: Char): Boolean {
        if (line == null) return false
        val first = splitCsv(line, sep).firstOrNull()?.trim()?.trim('"')?.lowercase() ?: return false
        return first in HEADER_WORDS
    }

    private val HEADER_WORDS = setOf(
        "word", "words", "term", "terms", "vocabulary", "english",
        "单词", "词汇", "词条", "英文", "英文单词"
    )


    private fun invalidReason(word: String): String? {
        if (word.isEmpty()) return "空"
        if (word.length > 64) return "过长"
        if (word.any { it in '\u4e00'..'\u9fff' }) return "含中文"
        if (word.none { it.isLetter() }) return "不含字母"
        val odd = word.count { !it.isLetter() && it != '-' && it != '\'' && it != ' ' && it != '.' }
        if (odd > 2) return "含异常符号"
        return null
    }

    private data class Decoded(val text: String, val encoding: String)

    /**
     * 依次尝试 UTF-8（含 BOM）/ UTF-16 / GBK
     * 和 TxtImporter 里那份逻辑相同，这里单独一份是为了不动文章导入那条路
     */
    private fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8（BOM）")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16 LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16 BE")
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return Decoded(it, "UTF-8") }
        runCatching { strictDecode(bytes, Charset.forName("GBK")) }
            .getOrNull()?.let { return Decoded(it, "GBK") }
        return null
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
