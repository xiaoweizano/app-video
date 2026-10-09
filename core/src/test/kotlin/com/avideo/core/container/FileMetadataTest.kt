package com.avideo.core.container

import com.avideo.core.errors.MalformedContainerError
import com.avideo.core.errors.UncommittedFileError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileMetadataTest {

    private val sha = ByteArray(32) { (it * 5).toByte() }

    @Test
    fun `round trip keeps name duration and hash`() {
        val original = FileMetadata("测试.mp4", 12_345L, sha)
        val decoded = FileMetadata.decode(FileMetadata.encode(original, 1024))
        assertEquals(original, decoded)
        assertEquals(12_345L, decoded.durationMs)
        assertContentEquals(sha, decoded.sha256)
    }

    @Test
    fun `encoded region is exactly metadata len`() {
        assertEquals(1024, FileMetadata.encode(FileMetadata("a.mp4", 1L, sha), 1024).size)
        assertEquals(4096, FileMetadata.encode(FileMetadata("a.mp4", 1L, sha), 4096).size)
    }

    @Test
    fun `real len equals 45 plus name len and padding is zero`() {
        val encoded = FileMetadata.encode(FileMetadata("abc.mp4", 1L, sha), 1024)
        val nameLen = 7
        val realLen = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(45 + nameLen, realLen)
        assertTrue(encoded.copyOfRange(realLen, 1024).all { it == 0.toByte() }, "零填充区必须全零")
    }

    @Test
    fun `placeholder encodes real_len zero`() {
        val placeholder = FileMetadata.encodePlaceholder(1024)
        assertEquals(1024, placeholder.size)
        assertTrue(placeholder.all { it == 0.toByte() })
        assertTrue(FileMetadata.isUncommitted(placeholder))
        assertFailsWith<UncommittedFileError> { FileMetadata.decode(placeholder) }
    }

    /** Review Focus 第 5 条：超长显示名必须按码点边界截断，不能切出非法 UTF-8。 */
    @Test
    fun `long display name is truncated at a codepoint boundary`() {
        val name = "视频".repeat(500) + ".mp4"        // 每个汉字 3 字节，远超 979
        val decoded = FileMetadata.decode(FileMetadata.encode(FileMetadata(name, 7L, sha), 1024))
        assertTrue(decoded.displayName.toByteArray(Charsets.UTF_8).size <= 979)
        assertTrue(name.startsWith(decoded.displayName), "截断结果必须是原名的前缀")
        assertEquals(7L, decoded.durationMs)
        assertContentEquals(sha, decoded.sha256)
    }

    /**
     * 979 不是 3 的倍数，所以按字节硬截断会切在汉字中间。
     *
     * **不能用 `bytes.size % 3 == 0` 来判**：被切断的那个残缺字节会解码成 U+FFFD（3 字节），
     * 于是重编码后的长度恰好又落回 3 的倍数——断言在正确实现和字节截断下**都会通过**，什么也分辨不了。
     * 真正能分辨的是「有没有出现替换字符」：按码点截断永远不会产出 U+FFFD。
     */
    @Test
    fun `truncation never splits a multi byte character`() {
        val name = "中".repeat(400)
        val encoded = FileMetadata.encode(FileMetadata(name, 1L, sha), 1024)
        val decoded = FileMetadata.decode(encoded)
        val bytes = decoded.displayName.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= 979, "截断结果超预算：${bytes.size} 字节")
        assertFalse(
            decoded.displayName.contains('�'),
            "显示名里出现了替换字符，说明截断切在了多字节字符中间",
        )
        assertTrue(decoded.displayName.all { it == '中' }, "截断结果必须仍是原名的前缀")
    }

    /**
     * toString 是诊断输出。断言要**照着真实泄漏的样子**写：
     * 朴素泄漏是直接插值数组（`"$sha256"` / `contentToString()`），
     * 而不是某个凭空挑出来的字面量串——后者永远不会出现，等于没断言。
     */
    @Test
    fun `toString does not print the display name or the hash bytes`() {
        val name = "我的私人视频.mp4"
        val text = FileMetadata(name, 1234L, sha).toString()
        assertFalse(text.contains(name), "toString 泄漏了显示名：$text")
        assertFalse(text.contains(sha.contentToString()), "toString 打印了哈希字节：$text")
        assertTrue(text.contains("displayNameLength"), "toString 应当只给出结构信息：$text")
    }

    @Test
    fun `exactly 979 bytes of ascii is not truncated`() {
        val name = "a".repeat(979)
        assertEquals(name, FileMetadata.decode(FileMetadata.encode(FileMetadata(name, 1L, sha), 1024)).displayName)
    }

    @Test
    fun `duration sentinel reads back as unknown`() {
        val metadata = FileMetadata("x.mp4", ContainerConstants.DURATION_UNKNOWN, sha)
        val decoded = FileMetadata.decode(FileMetadata.encode(metadata, 1024))
        assertEquals(ContainerConstants.DURATION_UNKNOWN, decoded.durationMs)
        assertFalse(decoded.durationKnown, "哨兵值必须被识别为「未知」而不是一个极大的时长")
    }

    @Test
    fun `non zero flags are rejected`() {
        val encoded = FileMetadata.encode(FileMetadata("x.mp4", 1L, sha), 1024)
        val flagsOffset = 4 + "x.mp4".toByteArray().size + 8 + 32
        encoded[flagsOffset] = 0x01
        assertFailsWith<MalformedContainerError> { FileMetadata.decode(encoded) }
    }

    @Test
    fun `inconsistent real len is rejected`() {
        val encoded = FileMetadata.encode(FileMetadata("x.mp4", 1L, sha), 1024)
        encoded[1] = (encoded[1] + 1).toByte()      // real_len 被改
        assertFailsWith<MalformedContainerError> { FileMetadata.decode(encoded) }
    }

    /** 预留区连固定字段都装不下时必须**抛异常**，而不是在截断循环里挂死。 */
    @Test
    fun `metadata reserve smaller than the fixed fields is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FileMetadata.encode(FileMetadata("a.mp4", 1L, sha), 44)
        }
    }

    @Test
    fun `large metadata reserve still works`() {
        assertEquals(4096, FileMetadata.encode(FileMetadata("a.mp4", 1L, sha), 4096).size)
    }
}
