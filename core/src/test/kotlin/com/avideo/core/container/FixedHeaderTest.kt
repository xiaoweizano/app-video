package com.avideo.core.container

import com.avideo.core.errors.MalformedContainerError
import com.avideo.core.errors.UnsupportedContainerVersionError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FixedHeaderTest {

    private fun header(plaintextLen: Long = 4_000_000L, metadataLen: Int = 1024) = FixedHeader(
        version = ContainerConstants.VERSION,
        wrappedFileKey = ByteArray(60) { (it + 1).toByte() },
        baseNonce = byteArrayOf(9, 8, 7, 6),
        plaintextLen = plaintextLen,
        metadataLen = metadataLen,
    )

    @Test
    fun `encoded header is exactly eighty one bytes`() {
        assertEquals(81, header().encode().size)
        assertEquals(ContainerConstants.FIXED_HEADER_LEN, header().encode().size)
    }

    @Test
    fun `field offsets add up to the header length`() {
        assertEquals(0, FixedHeader.OFF_MAGIC)
        assertEquals(4, FixedHeader.OFF_VERSION)
        assertEquals(5, FixedHeader.OFF_WRAPPED_FILE_KEY)
        assertEquals(65, FixedHeader.OFF_BASE_NONCE)
        assertEquals(69, FixedHeader.OFF_PLAINTEXT_LEN)
        assertEquals(77, FixedHeader.OFF_METADATA_LEN)
        assertEquals(81, FixedHeader.OFF_METADATA_LEN + ContainerConstants.METADATA_LEN_LEN)
    }

    @Test
    fun `encode decode round trip`() {
        val original = header(plaintextLen = 2L * ContainerConstants.CHUNK_PLAINTEXT + 5)
        // FixedHeader 覆写了内容相等的 equals，这里正是用它做往返比较。
        assertEquals(original, FixedHeader.decode(original.encode()))
    }

    @Test
    fun `unsigned metadata len and plaintext len survive`() {
        val original = header(plaintextLen = 5_000_000_000L, metadataLen = 4096)
        val decoded = FixedHeader.decode(original.encode())
        assertEquals(5_000_000_000L, decoded.plaintextLen)
        assertEquals(4096, decoded.metadataLen)
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = header().encode().also { it[0] = 0x00 }
        assertFailsWith<MalformedContainerError> { FixedHeader.decode(bytes) }
    }

    @Test
    fun `unsupported version is rejected`() {
        val bytes = header().encode().also { it[FixedHeader.OFF_VERSION] = 2 }
        assertFailsWith<UnsupportedContainerVersionError> { FixedHeader.decode(bytes) }
    }

    @Test
    fun `wrong length is rejected`() {
        assertFailsWith<MalformedContainerError> { FixedHeader.decode(ByteArray(80)) }
        assertFailsWith<MalformedContainerError> { FixedHeader.decode(ByteArray(82)) }
    }

    @Test
    fun `metadata len out of range is rejected`() {
        val bytes = header().encode()
        java.nio.ByteBuffer.wrap(bytes, FixedHeader.OFF_METADATA_LEN, 4)
            .order(java.nio.ByteOrder.BIG_ENDIAN).putInt(-1)
        assertFailsWith<MalformedContainerError> { FixedHeader.decode(bytes) }
    }

    /** 日志约束：toString 不许带出任何密钥字节。 */
    @Test
    fun `toString does not leak key bytes`() {
        val text = header().toString()
        assertFalse(text.contains("1, 2, 3"), "toString 泄漏了 wrapped_file_key：$text")
        assertEquals(true, text.contains("redacted"))
    }
}
