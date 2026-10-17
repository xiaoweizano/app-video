package com.avideo.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class RangeHeaderTest {

    private val len = 10_000L

    private fun bytes(start: Long, end: Long) = RangeSpec.Bytes(start, end)

    @Test
    fun `no header means the whole file`() {
        assertEquals(RangeSpec.Whole, parseRange(null, len))
        assertEquals(RangeSpec.Whole, parseRange("", len))
        assertEquals(RangeSpec.Whole, parseRange("   ", len))
    }

    @Test
    fun `a plain closed range is honored`() {
        assertEquals(bytes(1000, 1999), parseRange("bytes=1000-1999", len))
    }

    @Test
    fun `an open ended range runs to the last byte`() {
        assertEquals(bytes(1000, 9999), parseRange("bytes=1000-", len))
    }

    @Test
    fun `a suffix range means the last n bytes`() {
        assertEquals(bytes(9500, 9999), parseRange("bytes=-500", len))
        // 后缀比文件还大 ⇒ 整个文件（裁剪，不报错）
        assertEquals(bytes(0, 9999), parseRange("bytes=-999999", len))
    }

    @Test
    fun `an end past the file is clamped, not rejected`() {
        assertEquals(bytes(0, 9999), parseRange("bytes=0-999999999", len))
    }

    /** spec: playback-bridge「越界区间 MUST 返回 416」。 */
    @Test
    fun `a start at or past the end is unsatisfiable`() {
        assertEquals(RangeSpec.Unsatisfiable, parseRange("bytes=10000-", len))
        assertEquals(RangeSpec.Unsatisfiable, parseRange("bytes=99999999-99999999", len))
    }

    /** 空文件：任何区间都越界。这条守的是「不要在 read(0,n) 上撞边界」。 */
    @Test
    fun `any range on an empty file is unsatisfiable`() {
        assertEquals(RangeSpec.Unsatisfiable, parseRange("bytes=0-", 0L))
        assertEquals(RangeSpec.Unsatisfiable, parseRange("bytes=-1", 0L))
    }

    /** 语法认识但无意义（end < start）：RFC 要求忽略该头。 */
    @Test
    fun `an inverted range is ignored`() {
        assertEquals(RangeSpec.Whole, parseRange("bytes=500-100", len))
    }

    @Test
    fun `unparseable values are ignored rather than treated as errors`() {
        assertEquals(RangeSpec.Whole, parseRange("items=0-1", len))
        assertEquals(RangeSpec.Whole, parseRange("bytes=abc-def", len))
        assertEquals(RangeSpec.Whole, parseRange("bytes=", len))
        assertEquals(RangeSpec.Whole, parseRange("bytes=-", len))
        assertEquals(RangeSpec.Whole, parseRange("bytes=0", len))
        assertEquals(RangeSpec.Whole, parseRange("bytes=999999999999999999999999-", len))
    }

    @Test
    fun `a zero length suffix is unsatisfiable`() {
        assertEquals(RangeSpec.Unsatisfiable, parseRange("bytes=-0", len))
    }

    /**
     * 多区间：只取第一段。回 200 全文会让播放器一次性把整个文件读进来 ——
     * 对一个 2 GB 的视频就是内存灾难。
     */
    @Test
    fun `multiple ranges use only the first`() {
        assertEquals(bytes(0, 9), parseRange("bytes=0-9,20-29", len))
    }

    @Test
    fun `unit matching is case insensitive`() {
        assertEquals(bytes(0, 9), parseRange("Bytes=0-9", len))
    }

    @Test
    fun `the last single byte can be requested`() {
        assertEquals(bytes(9999, 9999), parseRange("bytes=9999-", len))
    }
}
