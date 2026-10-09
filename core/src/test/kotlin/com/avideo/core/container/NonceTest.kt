package com.avideo.core.container

import java.util.HashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NonceTest {

    private val base = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    /** v3 相对 v1 唯一会静默损坏数据的改动：metadata 占了 counter(1)。 */
    @Test
    fun `header metadata and data chunk nonces never collide`() {
        val all = listOf(
            Nonce.header(base),
            Nonce.metadata(base),
            Nonce.chunk(base, 0),
            Nonce.chunk(base, 1),
        )
        assertEquals(4, all.size)
        assertEquals(12, all[0].size)
        val seen = HashSet<String>()
        for (n in all) assertTrue(seen.add(n.joinToString(",") { "%02x".format(it) }), "nonce 重号")
    }

    @Test
    fun `data chunks start at counter two`() {
        val expected = ByteArray(12)
        base.copyInto(expected, 0)
        expected[11] = 2   // counter(2) 大端序
        assertTrue(Nonce.chunk(base, 0).contentEquals(expected), "chunk_0 必须用 counter(2)")
    }

    @Test
    fun `all counters of a thousand chunks are distinct and at least two`() {
        val seen = HashSet<String>()
        for (i in 0 until 1000) {
            val n = Nonce.chunk(base, i.toLong())
            assertTrue(seen.add(n.joinToString(",") { "%02x".format(it) }))
            val counter = java.nio.ByteBuffer.wrap(n, 4, 8).order(java.nio.ByteOrder.BIG_ENDIAN).long
            assertTrue(counter >= 2, "counter 必须 ≥ 2，实际 $counter")
        }
        assertEquals(1000, seen.size)
    }
}
