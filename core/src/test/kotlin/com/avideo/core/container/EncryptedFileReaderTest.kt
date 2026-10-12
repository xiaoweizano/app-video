package com.avideo.core.container

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.errors.HeaderAuthenticationFailedError
import com.avideo.core.errors.KeyUnwrapFailedError
import com.avideo.core.errors.MetadataAuthenticationFailedError
import com.avideo.core.errors.RangeOutOfBoundsError
import com.avideo.core.errors.TruncatedContainerError
import com.avideo.core.errors.UncommittedFileError
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EncryptedFileReaderTest {

    private val masterKey = ByteArray(32) { (it * 11 + 3).toByte() }
    private val p = ContainerConstants.CHUNK_PLAINTEXT
    private val dir by lazy { tempDir() }

    private fun write(
        plaintext: ByteArray,
        name: String = "播放素材.mkv",
        metadataLen: Int = ContainerConstants.METADATA_LEN,
    ): File {
        val file = File(dir, "read-${plaintext.size}-${System.nanoTime()}.bin")
        EncryptedFileWriter(FileContainerChannel.openForWrite(file), masterKey, metadataLen, DeterministicRandomSource(9))
            .use { it.writeFrom(ByteArrayInputStream(plaintext), name, plaintext.size.toLong(), 4_321L) }
        return file
    }

    private fun open(file: File): EncryptedFileReader =
        EncryptedFileReader.open(FileContainerChannel.openForRead(file), masterKey)

    @Test
    fun `metadata is readable after commit`() {
        val plaintext = patternBytes(p + 11)
        val file = write(plaintext)
        open(file).use { reader ->
            assertEquals(plaintext.size.toLong(), reader.plaintextLength)
            assertEquals("播放素材.mkv", reader.metadata.displayName)
            assertEquals(4_321L, reader.metadata.durationMs)
            assertContentEquals(
                java.security.MessageDigest.getInstance("SHA-256").digest(plaintext),
                reader.metadata.sha256,
            )
        }
    }

    /**
     * Review Focus 第 2 条的另一半（Task 7 只断言了文件大小）：
     * 恰好整块时不得多出/少掉一块。`chunkCount` 写成 `len / P` 只有这里能抓住。
     */
    @Test
    fun `content round trips for every boundary length`() {
        for (len in intArrayOf(1, p - 1, p, p + 1, 2 * p, 2 * p + 1)) {
            val plaintext = patternBytes(len)
            open(write(plaintext)).use { reader ->
                assertEquals(len.toLong(), reader.plaintextLength, "长度 $len 的 plaintextLength 不符")
                assertContentEquals(plaintext, reader.read(0L, len), "长度 $len 的往返不一致")
            }
        }
    }

    /** design.md D3：读方必须按**文件头里那个数**算 data_start，而不是假定 1024。 */
    @Test
    fun `metadata len other than one thousand twenty four`() {
        val plaintext = patternBytes(p + 9)
        val file = write(plaintext, name = "测试素材.mp4", metadataLen = 4096)
        open(file).use { reader ->
            assertEquals(4096, reader.metadataLen)
            assertEquals("测试素材.mp4", reader.metadata.displayName)
            assertContentEquals(plaintext, reader.read(0L, plaintext.size))
        }
    }

    /**
     * **长度被修正过的文件必须读得回来。** 这是 commit 里「重写头部」那一步的唯一端到端验证。
     *
     * 为什么必须有这一条：`writeFrom` 允许调用方声明的长度不准（计划 D-2），commit 会据此重写
     * `plaintext_len` **并重算 `header_tag`**。若重算那一步退回用占位头（或 metadata 的 AAD 用了旧头），
     * 所有测试仍会全绿，而**每一个长度被修正过的文件在读取时都打不开** —— 因为 T7 的测试全部
     * 传的是准确长度，走不到这条分支。这里故意让声明值与实读值不同，把那条路径压出来。
     */
    @Test
    fun `a file whose declared length was wrong is still readable`() {
        val plaintext = patternBytes(p + 5)
        val file = File(dir, "length-corrected.bin")
        EncryptedFileWriter(
            FileContainerChannel.openForWrite(file),
            masterKey,
            ContainerConstants.METADATA_LEN,
            DeterministicRandomSource(31),
        ).use {
            // 声明 12 字节，实际 p+5 —— 与真实值不同，触发 commit 里的头部重写分支
            it.writeFrom(ByteArrayInputStream(plaintext), "长度被修正.mp4", 12L, 4242L)
        }

        open(file).use { reader ->
            assertEquals(plaintext.size.toLong(), reader.plaintextLength)
            assertEquals("长度被修正.mp4", reader.metadata.displayName)
            assertEquals(4242L, reader.metadata.durationMs)
            assertContentEquals(plaintext, reader.read(0L, plaintext.size))
            assertContentEquals(plaintext.copyOfRange(p, plaintext.size), reader.read(p.toLong(), 5))
        }
    }

    /** Review Focus 第 3 条：块边界与「刚好读到末尾」。 */
    @Test
    fun `range reads at every boundary`() {
        val plaintext = patternBytes(2 * p + 5)
        val file = write(plaintext)
        open(file).use { reader ->
            val cases = listOf(
                0L to 1,
                0L to p,
                1L to 1,
                (p - 1).toLong() to 1,
                p.toLong() to 1,
                (p - 10).toLong() to 20,
                (p + 1).toLong() to 5,
                p.toLong() to p,
                (2L * p).toLong() to 5,
                (2L * p).toLong() to 1,
                (2L * p + 4).toLong() to 1,
                (plaintext.size - 1).toLong() to 1,
                (plaintext.size - 5).toLong() to 5,
            )
            for ((offset, len) in cases) {
                val expected = plaintext.copyOfRange(offset.toInt(), offset.toInt() + len)
                assertContentEquals(expected, reader.read(offset, len), "区间 [$offset, +$len) 不符")
            }
        }
    }

    @Test
    fun `read spanning exactly to the end of the file`() {
        val plaintext = patternBytes(p + 7)
        val file = write(plaintext)
        open(file).use { reader ->
            assertContentEquals(
                plaintext.copyOfRange(3, plaintext.size),
                reader.read(3L, plaintext.size - 3),
            )
        }
    }

    /** spec: container-format「越界区间被拒绝」。 */
    @Test
    fun `out of bounds ranges are rejected without truncation`() {
        val plaintext = patternBytes(1000)
        val file = write(plaintext)
        open(file).use { reader ->
            assertFailsWith<RangeOutOfBoundsError> { reader.read(1000L, 1) }
            assertFailsWith<RangeOutOfBoundsError> { reader.read(999L, 2) }
            assertFailsWith<RangeOutOfBoundsError> { reader.read(-1L, 1) }
            // 边界内必须照常工作，证明拒绝不是「一律抛错」
            assertContentEquals(plaintext.copyOfRange(999, 1000), reader.read(999L, 1))
        }
    }

    /**
     * 越界判据 MUST NOT 依赖 `offset + len`：那个加法在 `offset > Long.MAX_VALUE - len` 时溢出成负数，
     * 于是「越界」被算成「越界内」，一路走到 `ByteArray(sealedLen)` 抛
     * `NegativeArraySizeException` / `IndexOutOfBoundsException` —— 都不是 [VaultError] 家族。
     *
     * 偏移来自外部输入（stage-2 的 Range 请求要能对越界返回 416），所以这必须是一条干净的区间错误。
     */
    @Test
    fun `an offset that would overflow the bounds check is still a clean range error`() {
        val plaintext = patternBytes(1000)
        val file = write(plaintext)
        open(file).use { reader ->
            // `Long.MAX_VALUE + 1` 回绕成负数，旧的 `offset + len > plaintextLength` 判据抓不住
            assertFailsWith<RangeOutOfBoundsError> { reader.read(Long.MAX_VALUE, 1) }
            assertFailsWith<RangeOutOfBoundsError> { reader.read(Long.MAX_VALUE - 3, 8) }
            // offset 恰好等于末尾、len > 0：减法形式下 `plaintextLength - offset == 0`，必须判越界
            assertFailsWith<RangeOutOfBoundsError> { reader.read(1000L, 1) }
            // 末尾的**空**读取仍必须成功（减法形式不该把它误判成越界）
            assertEquals(0, reader.read(1000L, 0).size)
        }
    }

    /** Review Focus 第 4 条：截断必须当场判定。 */
    @Test
    fun `truncated file is detected`() {
        val plaintext = patternBytes(2 * p)
        val file = write(plaintext)
        val truncated = File(dir, "truncated.bin")
        truncated.writeBytes(file.readBytes().copyOf(file.length().toInt() - 100))
        assertFailsWith<TruncatedContainerError> {
            open(truncated).use { it.read(0L, 16) }
        }
    }

    @Test
    fun `tampered header is rejected before data_start is used`() {
        val file = write(patternBytes(4096))
        val bytes = file.readBytes()
        bytes[FixedHeader.OFF_PLAINTEXT_LEN + 7] = (bytes[FixedHeader.OFF_PLAINTEXT_LEN + 7] + 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<HeaderAuthenticationFailedError> { open(file) }
    }

    @Test
    fun `tampered metadata len is caught by the header tag`() {
        val file = write(patternBytes(4096))
        val bytes = file.readBytes()
        bytes[FixedHeader.OFF_METADATA_LEN + 3] = 8      // 1024 → 1032
        file.writeBytes(bytes)
        // metadata_len 参与 header_tag 的 AAD，改它必然让某个环节失败。
        // 关键断言是「拒绝」——MUST NOT 拿着被篡改的值去算 data_start 然后返回数据。
        // 这里的实际路径：新 tag 偏移处读到的不是全零 → 解包成功 → header_tag 校验失败。
        assertFailsWith<HeaderAuthenticationFailedError> { open(file) }
    }

    @Test
    fun `tampered metadata body fails its tag`() {
        val file = write(patternBytes(4096))
        val bytes = file.readBytes()
        val offset = OffsetMapping.METADATA_OFFSET.toInt() + 40
        bytes[offset] = (bytes[offset] + 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<MetadataAuthenticationFailedError> { open(file) }
    }

    @Test
    fun `wrong master key cannot open`() {
        val file = write(patternBytes(4096))
        val wrong = ByteArray(32) { 0x55 }
        assertFailsWith<KeyUnwrapFailedError> {
            EncryptedFileReader.open(FileContainerChannel.openForRead(file), wrong)
        }
    }

    /**
     * 「主密钥不对」是**最常见**的失败路径（用户输错一次密码，就要扫过仓库里每一个文件）。
     * 它必须和别的失败一样归还 channel —— 否则一次错密码就是一次全库 fd 泄漏。
     * 这条钉的是「整段 open 都在同一个 try 里」，而不是只把解包之后的半段包起来。
     */
    @Test
    fun `a failed unwrap releases the channel too`() {
        val file = write(patternBytes(4096))
        val channel = CountingChannel(FileContainerChannel.openForRead(file))
        assertFailsWith<KeyUnwrapFailedError> {
            EncryptedFileReader.open(channel, ByteArray(32) { 0x55 })
        }
        assertTrue(channel.closed, "主密钥不对时 channel 未被归还（最常见的失败路径在漏 fd）")
    }

    /**
     * 未提交判据也在同一 try 里：命中它时同样必须归还 channel。
     * 恢复流程会扫出所有未提交文件，这条路径的调用次数不比正常路径少。
     */
    @Test
    fun `an uncommitted file releases the channel too`() {
        val file = File(dir, "uncommitted-channel.bin")
        val writer = EncryptedFileWriter(FileContainerChannel.openForWrite(file), masterKey, 1024, DeterministicRandomSource(3))
        writer.writePlaceholder(5000L)
        writer.writeChunk(patternBytes(1000), 1000)
        writer.close()

        val channel = CountingChannel(FileContainerChannel.openForRead(file))
        assertFailsWith<UncommittedFileError> { EncryptedFileReader.open(channel, masterKey) }
        assertTrue(channel.closed, "未提交文件被拒时 channel 未被归还")
    }

    @Test
    fun `tampered wrapped file key fails at unwrap, not later`() {
        val file = write(patternBytes(4096))
        val bytes = file.readBytes()
        bytes[FixedHeader.OFF_WRAPPED_FILE_KEY + 3] = (bytes[FixedHeader.OFF_WRAPPED_FILE_KEY + 3] + 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<KeyUnwrapFailedError> { open(file) }
    }

    /** Review Focus 第 1 条的另一半：未提交文件读不出来，但错误类型是「未提交」而不是「损坏」。 */
    @Test
    fun `uncommitted file raises uncommitted not corrupted`() {
        val file = File(dir, "uncommitted.bin")
        val writer = EncryptedFileWriter(FileContainerChannel.openForWrite(file), masterKey, 1024, DeterministicRandomSource(3))
        writer.writePlaceholder(5000L)
        writer.writeChunk(patternBytes(1000), 1000)
        writer.close()
        assertFailsWith<UncommittedFileError> { open(file) }
    }

    /**
     * commit 写到一半被杀留下的磁盘状态：metadata 正文是**新的**、tag 还是占位时的 16 个零。
     * 这条正是 design.md D4 担心「判据打架」的那个窗口——必须判「未提交」，
     * MUST NOT 判「仓库损坏」。
     */
    @Test
    fun `uncommitted file from a crash inside commit is not corrupted`() {
        val plaintext = patternBytes(4096)
        val file = write(plaintext)
        // commit 死于「头部已重写 / header_tag 尚未重算」之间、且 tag 未写时的磁盘状态：
        //   · metadata_tag 仍是占位时的 16 个零  → 无密码判据说「未提交」
        //   · header_tag 与当前头部字节不再匹配   → 有密码判据若先跑会判「损坏」
        // 顺序②必须压过④：这里必须报「未提交」。若把②挪到解包/验头之后，本条会变成
        // HeaderAuthenticationFailedError —— 恢复流程就失去了「认出中断的导入」的能力。
        val bytes = file.readBytes()
        val tagOffset = OffsetMapping.metadataTagOffset(1024).toInt()
        java.util.Arrays.fill(bytes, tagOffset, tagOffset + ContainerConstants.METADATA_TAG_LEN, 0)
        // 只动头部里被 header_tag 认证的一个字节，让 header_tag 校验必然失败。
        bytes[FixedHeader.OFF_PLAINTEXT_LEN + 7] = (bytes[FixedHeader.OFF_PLAINTEXT_LEN + 7] + 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<UncommittedFileError> { open(file) }
        // ②的核心是「**不必解包任何东西**就能判」。给一个错的主密钥也必须先判出「未提交」，
        // 而不是 KeyUnwrapFailedError —— 把②挪到③之后，这一条立刻变红。
        assertFailsWith<UncommittedFileError> {
            EncryptedFileReader.open(FileContainerChannel.openForRead(file), ByteArray(32) { 0x77 })
        }
    }

    @Test
    fun `closing the reader wipes the data key and refuses further reads`() {
        val plaintext = patternBytes(2000)
        val file = write(plaintext)
        val reader = open(file)
        // 断言内容而不只是长度：`read` 返回 `ByteArray(len)`，只比长度的话恒真。
        assertContentEquals(plaintext.copyOfRange(0, 8), reader.read(0L, 8))
        // 关闭前 data key 非零（否则「清零」无从谈起）
        val keyField = reader.javaClass.getDeclaredField("dataKey").apply { isAccessible = true }
        assertTrue((keyField.get(reader) as ByteArray).any { it != 0.toByte() }, "关闭前 data key 不应是全零")
        reader.close()
        // 关闭后 key 必须被清零（全局约束：close() MUST wipe the data key array）
        assertTrue((keyField.get(reader) as ByteArray).all { it == 0.toByte() }, "关闭后 data key 未被清零")
        assertFailsWith<IllegalStateException> { reader.read(0L, 8) }
    }

    @Test
    fun `reader does not touch the filesystem while reading a range`() {
        val file = write(patternBytes(2 * p + 5))
        val before = file.readBytes()
        open(file).use { it.read(p.toLong() + 3, 4096) }
        assertContentEquals(before, file.readBytes(), "读取路径改动了磁盘内容")
    }

    /**
     * `open` 在解包出 data key **之后**若因 I/O 失败中止，MUST 关掉调用方交进来的 channel
     * （open 成功时它的 close() 就会关，失败时不能例外，否则扫一遍满是损坏文件的目录
     * 会一条一个 fd 泄漏到 GC），并且 MUST 让原始异常原样传播、不被吞掉或换成别的类型。
     *
     * 这里的替身在第 4 次 readAt（⑤ 读 metadata 区）抛 IOException —— 那一次调用发生在
     * ③ 解包之后，恰好落在「已经持有 data key」的窗口里。同时这条也覆盖了
     * 「单条 catch-all 同时负责清 key 与关 channel」这个结构。
     */
    @Test
    fun `a failing open closes the channel and propagates the io error`() {
        val file = write(patternBytes(4096))

        // 先数出一次成功 open 会做几次 readAt，确认第 4 次确实落在解包之后。
        val probe = CountingChannel(FileContainerChannel.openForRead(file))
        EncryptedFileReader.open(probe, masterKey).close()
        assertTrue(probe.reads >= 4, "open 应当至少读 4 次（头/tag/header tag/metadata），实际 ${probe.reads}")

        val failing = ThrowingChannel(FileContainerChannel.openForRead(file), failAtRead = 3)
        assertFailsWith<java.io.IOException> { EncryptedFileReader.open(failing, masterKey) }
        assertTrue(failing.closed, "open 失败后 channel 未被关闭（fd 泄漏）")
    }
}

/** 数 readAt 次数、记录 close 是否被调用，其余转发给 delegate。 */
private class CountingChannel(private val delegate: ContainerChannel) : ContainerChannel {
    var reads = 0
    var closed = false
    override fun writeAt(offset: Long, src: ByteArray, length: Int) = delegate.writeAt(offset, src, length)
    override fun readAt(offset: Long, dst: ByteArray, length: Int): Int {
        reads++
        return delegate.readAt(offset, dst, length)
    }
    override fun size(): Long = delegate.size()
    override fun flush() = delegate.flush()
    override fun close() { closed = true; delegate.close() }
}

/** 在第 [failAtRead] 次 readAt 上抛 IOException，并记录 close 是否被调用。 */
private class ThrowingChannel(
    private val delegate: ContainerChannel,
    private val failAtRead: Int,
) : ContainerChannel {
    var reads = 0
    var closed = false
    override fun writeAt(offset: Long, src: ByteArray, length: Int) = delegate.writeAt(offset, src, length)
    override fun readAt(offset: Long, dst: ByteArray, length: Int): Int {
        if (reads == failAtRead) throw java.io.IOException("simulated storage failure")
        reads++
        return delegate.readAt(offset, dst, length)
    }
    override fun size(): Long = delegate.size()
    override fun flush() = delegate.flush()
    override fun close() { closed = true; delegate.close() }
}
