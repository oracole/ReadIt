package com.oracole.readit.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

sealed interface ImportResult {
    data class Ok(val text: String, val fileName: String) : ImportResult
    data class Err(val message: String) : ImportResult
}

object TxtImporter {

    private const val MAX_BYTES = 5 * 1024 * 1024



    suspend fun read(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = queryName(ctx, uri) ?: "未命名.txt"

        // 流式读取，超限立即中止
        // 原来先 readBytes() 再检查大小，选一个几百 MB 的 txt 会先 OOM
        // 而 catch(Exception) 抓不到 OutOfMemoryError（它是 Error 不是 Exception）
        // 文件超过 5 MB那句提示永远走不到
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_BYTES) {
                        return@withContext ImportResult.Err("文件超过 5 MB，建议分段导入")
                    }
                }
                out.toByteArray()
            } ?: return@withContext ImportResult.Err("打不开这个文件，可能已损坏")
        } catch (e: Exception) {
            return@withContext ImportResult.Err("打不开这个文件，可能已损坏")
        }

        if (bytes.isEmpty()) return@withContext ImportResult.Err("这个文件是空的")

        val text = decode(bytes) ?: return@withContext ImportResult.Err("无法识别文件编码，请确认是纯文本")
        val cleaned = normalize(text)
        if (cleaned.isBlank()) return@withContext ImportResult.Err("这个文件没有可读的文字")
        ImportResult.Ok(cleaned, name)
    }

    /** 依次尝试 UTF-8 / UTF-16 / GBK，失败返回 null */
    private fun decode(bytes: ByteArray): String? {
        // BOM 判定
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

        // 无 BOM 的 UTF-16 要先挡掉
        // ASCII 文本存成 UTF-16 时有一半字节是 0x00，而 0x00 是合法 UTF-8
        // 严格解码不会报错，会得到 "a\u0000b\u0000c\u0000"，
        // 分词按空白切，NUL 不是空白，整段会粘成一个"词"
        guessBomlessUtf16(bytes)?.let { cs ->
            runCatching { strictDecode(bytes, cs) }.getOrNull()?.let { return it }
        }

        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        strictDecode(bytes, Charset.forName("GBK"))?.let { return it }

        // 两种严格解码都失败时的兜底
        //
        // 已知限制（实测过，不是猜的）：
        // Big5 / Shift-JIS 的中文/日文会被 GBK成功解码成乱码
        // 根本走不到这里，例如這是一段中文(Big5) 会变成硂琌琿いゅ
        // 所以靠这个分支拦不住错误编码，用户看到的是"导入成功"但正文乱码
        //
        // 要可靠地识别需要完整的字频表，做不划算；用假名比例之类的启发式
        // 只能命中一部分（实测约一半），反而制造"看起来处理了"的假象
        // 这一条明确不做，等有真实用户反馈再谈
        //
        // 保留兜底是为了另一种情况：从 PDF 复制的文本常带零星私有区字符
        // 严格解码会失败，替换后能救回绝大部分内容，那时宁可要内容
        return String(bytes, Charsets.UTF_8)
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

    /** 归一化：统一换行、去多余空行、去零宽字符 */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var newlines = 0
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                // CR 也是换行。直接丢弃会让 CR-only 的文件（旧 Mac、部分导出工具）
                // 整篇没有换行，段落切分塌成一段
                // CRLF 只算一次，所以看下一个字符是不是 LF，是就交给它处理
                ch == '\r' -> {
                    if (i + 1 >= raw.length || raw[i + 1] != '\n') {
                        newlines++
                        if (newlines <= 2) sb.append('\n')
                    }
                }
                ch == '\uFEFF' || ch == '\u200B' -> {}
                ch == '\n' -> {
                    newlines++
                    if (newlines <= 2) sb.append('\n')
                }
                else -> { newlines = 0; sb.append(ch) }
            }
            i++
        }
        return sb.toString().trim()
    }

    /**
     * 无 BOM 的 UTF-16 判别
     *
     * 取前 4 KB，按 16 位为单位统计 0x00 落在高字节还是低字节
     * 英文文本存成 UTF-16 时，绝大多数码位小于 0x0100
     * 所以必有一半字节是 0x00，这个特征足够可靠
     *
     * 返回值是猜测的编码，拿不准就返回 null，交给原来的顺序
     */
    private fun guessBomlessUtf16(bytes: ByteArray): Charset? {
        if (bytes.size < 16 || bytes.size % 2 != 0) return null
        val limit = minOf(bytes.size, 4096)
        var evenZero = 0
        var oddZero = 0
        var pairs = 0
        var i = 0
        while (i + 1 < limit) {
            if (bytes[i] == 0.toByte()) evenZero++
            if (bytes[i + 1] == 0.toByte()) oddZero++
            pairs++
            i += 2
        }
        if (pairs == 0) return null
        val ratio = pairs.toFloat()
        // 低字节是 ASCII、高字节是 0 → UTF-16LE（最常见）
        if (oddZero / ratio > 0.6f && evenZero / ratio < 0.2f) return Charsets.UTF_16LE
        if (evenZero / ratio > 0.6f && oddZero / ratio < 0.2f) return Charsets.UTF_16BE
        return null
    }

    /** 仅供单元测试：走「字节 → 解码 → 归一化」全链路 */
    internal fun decodeForTest(bytes: ByteArray): String? {
        val d = decode(bytes) ?: return null
        return normalize(d)
    }

    private fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
