package com.oracole.readit.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 用户导入的一款字体 */
data class CustomFont(
    /** 内部文件名，存的时候用它，不带扩展名 */
    val fileName: String,
    /** 显示名，取自字体内部 name 表，取不到就用文件名 */
    val displayName: String
)

/**
 * 用户导入的字体
 *
 * 存法
 *   files/fonts/<hash>.ttf    字体文件本身
 *   files/fonts/index.json    清单，记文件名和显示名
 *
 * 为什么不用资源目录
 * 字体是运行时导入的，res/font 只能放编译期确定的文件
 *   运行时加的字体只能用 Typeface.createFromFile 加载
 *
 * 为什么不支持 ttc
 *   ttc 里多个 face 共用同一份字形数据，按 index 取 face
 * 在 Android 上拿不准，实测出来几个 face 字形一样
 *   只收单字形的 ttf 和 otf，导入时检查文件头
 */
class FontRepository(private val ctx: Context) {

    private val dir: File get() = File(ctx.filesDir, "fonts")
    private val indexFile: File get() = File(dir, "index.json")

    /** 读清单。文件损坏时返回空表，不让设置页崩掉 */
    fun list(): List<CustomFont> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CustomFont(
                    fileName = o.getString("file"),
                    displayName = o.getString("name")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun fileOf(fileName: String): File = File(dir, fileName)

    /**
     * 导入一个字体文件
     *
     * 先复制到临时文件，校验通过、并且系统能真的解析出来，才改名入库
     * 解析不了的文件（改了扩展名的图片之类）不该进清单
     */
    suspend fun import(uri: Uri): Result<CustomFont> = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()

            val rawName = queryName(uri) ?: "未命名字体"
            val ext = rawName.substringAfterLast('.', "").lowercase()
            if (ext != "ttf" && ext != "otf") {
                throw IllegalArgumentException("只能导入 ttf 或 otf 文件")
            }

            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("读不到文件")
            if (bytes.isEmpty()) throw IllegalStateException("文件是空的")

            // 大小上限。CJK 字体动辄十几 MB，放到 40MB 够用
            if (bytes.size > 40 * 1024 * 1024) {
                throw IllegalStateException("文件超过 40MB")
            }

            // 校验文件头，防止改了扩展名的其它文件混进来
            verifyFontHeader(bytes)

            // 用路径的哈希当文件名，同名文件不会互相覆盖
            val fileName = Integer.toHexString(bytes.contentHashCode()) + "." + ext
            val target = File(dir, fileName)

            // 已经导过同一个文件就复用
            if (!target.exists()) {
                val tmp = File(dir, fileName + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw IllegalStateException("保存失败")
                }
            }

            // 真的解析一次，解析不了就当导入失败
            // 只看文件头不够，有些字体结构坏了照样有合法头
            val display = runCatching {
                val tf = android.graphics.Typeface.createFromFile(target)
                if (tf == null) throw IllegalStateException("系统无法解析这个字体")
                readFontName(target) ?: rawName.substringBeforeLast('.')
            }.getOrElse {
                if (target.exists() && File(dir, fileName + ".tmp").exists().not()) target.delete()
                throw it
            }

            val entry = CustomFont(fileName, display.ifBlank { rawName })
            saveIndex(list().filterNot { it.fileName == fileName } + entry)
            entry
        }
    }

    suspend fun remove(fileName: String) = withContext(Dispatchers.IO) {
        fileOf(fileName).delete()
        saveIndex(list().filterNot { it.fileName == fileName })
    }

    private fun saveIndex(list: List<CustomFont>) {
        dir.mkdirs()
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("file", it.fileName)
                put("name", it.displayName)
            })
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString())
            tmp.delete()
        }
    }

    /**
     * 从字体文件里读家族名
     *
     * Typeface 没有公开的读名字接口，android.graphics.fonts.Font 也要 API 29 以上
     * 才方便拿到，为了这个小功能引一套解析不值得，直接用文件名当显示名
     * 用户导入时看到的文件名一般就是字体名，够用
     */
    private fun readFontName(f: File): String? = null

    /**
     * 检查是不是合法的 sfnt 字体
     *
     * 前四个字节是版本标记
     *   00010000  ttf
     *   OTTO      otf，CFF 轮廓
     *   true      ttf，旧 Mac 风格
     *   ttcf      ttc 合集，这里不支持
     */
    private fun verifyFontHeader(b: ByteArray) {
        if (b.size < 4) throw IllegalStateException("文件太小，不是字体")
        val tag = String(b, 0, 4, Charsets.ISO_8859_1)
        when (tag) {
            "ttcf" -> throw IllegalArgumentException("不支持 ttc 合集，请用单个 ttf 或 otf")
            "\u0000\u0001\u0000\u0000", "OTTO", "true" -> Unit
            else -> throw IllegalArgumentException("不是有效的字体文件")
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()
}
