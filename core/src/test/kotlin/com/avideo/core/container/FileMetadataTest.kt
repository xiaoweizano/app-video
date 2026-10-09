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

    @Test
    fun `truncation never splits a multi byte character`() {
        // 979 − 1 = 978 不是 3 的倍数，硬按字节截断会切在汉字中间。
        val name = "中".repeat(400)
        val encoded = FileMetadata.encode(FileMetadata(name, 1L, sha), 1024)
        val decoded = FileMetadata.decode(encoded)
        val bytes = decoded.displayName.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size % 3 == 0, "截断落在了多字节字符中间：${bytes.size} 字节")
        assertEquals(String(bytes, Charsets.UTF_8), decoded.displayName)
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
}
