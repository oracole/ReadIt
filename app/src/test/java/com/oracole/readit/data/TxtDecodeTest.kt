package com.oracole.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * txt 导入的解码与归一化。
 *
 * 用例来自审阅方指出的三类真实故障：
 *   无 BOM 的 UTF-16 被当成 UTF-8（正文里夹 NUL）
 *   CR-only 换行被整个丢掉（整篇塌成一段）
 *   5 MB 检查来不及触发（改成流式读取，本文件不覆盖 IO 部分）
 */
class TxtDecodeTest {

    @Test
    fun `无 BOM 的 UTF-16LE 不会留下 NUL`() {
        val src = "Hello world.\nSecond line."
        val bytes = src.toByteArray(Charsets.UTF_16LE)
        val out = TxtImporter.decodeForTest(bytes)!!
        assertFalse("不应含 NUL", out.contains('\u0000'))
        assertEquals(src, out)
    }

    @Test
    fun `无 BOM 的 UTF-16BE 不会留下 NUL`() {
        val src = "Hello world.\nSecond line."
        val bytes = src.toByteArray(Charsets.UTF_16BE)
        val out = TxtImporter.decodeForTest(bytes)!!
        assertEquals(src, out)
    }

    @Test
    fun `带 BOM 的 UTF-16LE 仍然正确`() {
        val src = "BOM 测试\n第二行"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            src.toByteArray(Charsets.UTF_16LE)
        assertEquals(src, TxtImporter.decodeForTest(bytes))
    }

    @Test
    fun `UTF-8 不受影响`() {
        val src = "Hello world.\nSecond line."
        assertEquals(src, TxtImporter.decodeForTest(src.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `GBK 仍然识别`() {
        val src = "第一段\n\n第二段"
        assertEquals(src, TxtImporter.decodeForTest(src.toByteArray(charset("GBK"))))
    }

    @Test
    fun `CR-only 换行折算成换行`() {
        // 旧 Mac / 部分导出工具只发 \r。原来整篇没有换行，段落只剩一段
        val out = TxtImporter.decodeForTest("para one\rpara two".toByteArray(Charsets.UTF_8))!!
        assertTrue("应含换行", out.contains('\n'))
        assertFalse("不应残留 CR", out.contains('\r'))
        assertEquals("para one\npara two", out)
    }

    @Test
    fun `CRLF 只算一次换行`() {
        // \r 被跳过、交给 \n 处理，不能变成两个换行
        val out = TxtImporter.decodeForTest("a\r\nb".toByteArray(Charsets.UTF_8))!!
        assertEquals("a\nb", out)
    }

    @Test
    fun `连续三个以上换行压成两个`() {
        val out = TxtImporter.decodeForTest("a\r\n\r\n\r\n\r\nb".toByteArray(Charsets.UTF_8))!!
        assertEquals("a\n\nb", out)
    }

    @Test
    fun `CRLF 连续空行同样被压缩`() {
        val out = TxtImporter.decodeForTest("a\r\n\r\nb".toByteArray(Charsets.UTF_8))!!
        assertEquals("a\n\nb", out)
    }

    @Test
    fun `纯 ASCII 不会被误判成 UTF-16`() {
        // 没有 NUL 的普通文本必须走原路，不能被 guessBomlessUtf16 拦下
        val src = "abcdefghijklmnopqrstuvwxyz"
        assertEquals(src, TxtImporter.decodeForTest(src.toByteArray(Charsets.UTF_8)))
    }
    @Test
    fun `坏字节不会让导入整体失败`() {
        // 从 PDF 复制的文本常带零星非法字节，兜底替换后仍应导入
        val mixed = "hello world this is fine ".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x80.toByte())
        assertTrue("少量坏字节应接受", TxtImporter.decodeForTest(mixed) != null)
    }

    @Test
    fun `Big5 会被 GBK 误判 已知限制`() {
        // 记录现状：Big5 的「這是一段中文」会被 GBK 解成乱码而不是报错。
        // 这条测试是给未来的人看的 —— 说明这个坑存在且已知，不是疏漏。
        val big5 = "這是一段中文".toByteArray(charset("Big5"))
        val out = TxtImporter.decodeForTest(big5)
        assertTrue("当前会返回乱码而不是 null", out != null)
        assertTrue("且内容确实不是原文", out != "這是一段中文")
    }
}
