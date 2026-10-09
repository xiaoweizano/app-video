package com.avideo.core.container

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.RecordingChannel
import com.avideo.core.SimulatedCrashError
import com.avideo.core.errors.EmptySourceError
import com.avideo.core.indexOf
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EncryptedFileWriterTest {

    private val masterKey = ByteArray(32) { (it * 7 + 1).toByte() }
    private val p = ContainerConstants.CHUNK_PLAINTEXT
    private val dir by lazy { tempDir() }

    private fun writeSession(file: File, metadataLen: Int = ContainerConstants.METADATA_LEN) =
        EncryptedFileWriter(
            FileContainerChannel.openForWrite(file),
            masterKey,
            metadataLen,
            DeterministicRandomSource(seed = 20261009),
        )

    private fun writeFile(
        plaintext: ByteArray,
        name: String = "测试素材.mp4",
        metadataLen: Int = ContainerConstants.METADATA_LEN,
        declaredLen: Long = plaintext.size.toLong(),
    ): Pair<File, FileMetadata> {
        val file = File(dir, "case-${plaintext.size}-${System.nanoTime()}.bin")
        val writer = EncryptedFileWriter(
            FileContainerChannel.openForWrite(file),
            masterKey,
            metadataLen,
            DeterministicRandomSource(seed = 1),
        )
        val committed = writer.use {
            it.writeFrom(ByteArrayInputStream(plaintext), name, declaredLen, 98_765L)
        }
        return file to committed
    }

    /**
     * Review Focus 第 2 条：恰好整块时不得多写一块。
     * 内容往返（需要 reader）在 Task 8 的 `content round trips for every boundary length`。
     */
    @Test
    fun `file size is exactly right for every boundary length`() {
        for (len in intArrayOf(1, p - 1, p, p + 1, 2 * p, 2 * p + 1)) {
            val (file, _) = writeFile(patternBytes(len))
            assertEquals(
                OffsetMapping.expectedFileSize(len.toLong(), ContainerConstants.METADATA_LEN),
                file.length(),
                "长度 $len 的密文文件大小不符",
            )
        }
    }

    /** Review Focus 第 1 条：0 字节源必须被拒绝，而不是留下一个读不出内容的条目。 */
    @Test
    fun `empty source is rejected and leaves an uncommitted file`() {
        val file = File(dir, "empty.bin")
        val writer = writeSession(file)
        assertFailsWith<EmptySourceError> {
            writer.use { it.writeFrom(ByteArrayInputStream(ByteArray(0)), "empty.mp4", 0L) }
        }
        assertEquals(ContainerClassifier.State.UNCOMMITTED, classify(file))
    }

    /** Review Focus 第 2 条：恰好整块时不得多写一块。 */
    @Test
    fun `exact multiple of chunk size does not add a trailing chunk`() {
        val (file, _) = writeFile(patternBytes(p))
        assertEquals(OffsetMapping.dataStart(1024) + ContainerConstants.CHUNK_STRIDE, file.length())
        val (file2, _) = writeFile(patternBytes(2 * p))
        assertEquals(OffsetMapping.dataStart(1024) + 2 * ContainerConstants.CHUNK_STRIDE, file2.length())
    }

    @Test
    fun `plaintext pattern never appears in the ciphertext file`() {
        val marker = "AVLT-SECRET-MARK".toByteArray()
        val plaintext = patternBytes(3 * p + 123)
        marker.copyInto(plaintext, 5)
        marker.copyInto(plaintext, p + 9)
        marker.copyInto(plaintext, 2 * p + 1)
        val (file, _) = writeFile(plaintext)
        assertEquals(-1, indexOf(file.readBytes(), marker), "明文模式出现在密文中")
    }

    @Test
    fun `source stream is fully read exactly once`() {
        var readsAfterEof = 0
        val plaintext = patternBytes(p + 17)
        val counting = object : InputStream() {
            private val delegate = ByteArrayInputStream(plaintext)
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = delegate.read(b, off, len)
                if (n < 0) readsAfterEof++
                return n
            }
        }
        val file = File(dir, "counted.bin")
        val metadata = writeSession(file).use {
            it.writeFrom(counting, "counted.mp4", plaintext.size.toLong())
        }
        // 边写边算哈希：源流只允许走到 EOF 一次（结束后最多再问一次 EOF）
        assertTrue(readsAfterEof <= 1, "源流被反复重读：EOF 之后又问了 $readsAfterEof 次")
        // 哈希必须来自这一遍读取，而不是重读一遍源文件
        assertContentEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(plaintext),
            metadata.sha256,
        )
    }

    @Test
    fun `no temporary files are left behind`() {
        val solo = tempDir("avault-solo")
        val file = File(solo, "one.bin")
        writeSession(file).use { it.writeFrom(ByteArrayInputStream(patternBytes(4096)), "one.mp4", 4096L) }
        assertEquals(listOf("one.bin"), solo.list()!!.sorted())
    }

    @Test
    fun `hash in metadata matches an independent sha256`() {
        val plaintext = patternBytes(p + 5)
        val (_, committed) = writeFile(plaintext)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(plaintext)
        assertContentEquals(digest, committed.sha256)
    }

    @Test
    fun `declared length differing from the real length is corrected at commit`() {
        val plaintext = patternBytes(p + 5)
        val (file, committed) = writeFile(plaintext, declaredLen = 12L)
        assertEquals(plaintext.size.toLong(), committed.let { readHeader(file).plaintextLen })
        assertEquals(OffsetMapping.expectedFileSize(plaintext.size.toLong(), 1024), file.length())
        // 能按修正后的长度读回来，见 Task 8 的 `metadata is readable after commit`
    }

    @Test
    fun `plaintext len zero is representable`() {
        assertEquals(
            OffsetMapping.dataStart(1024),
            OffsetMapping.expectedFileSize(0L, 1024),
        )
    }

    /** spec: import-commit-protocol「占位后未提交」。 */
    @Test
    fun `placeholder alone is classified uncommitted`() {
        val file = File(dir, "placeholder.bin")
        val writer = writeSession(file)
        writer.writePlaceholder(4L * p)
        writer.close()
        assertEquals(ContainerClassifier.State.UNCOMMITTED, classify(file))
        assertEquals(
            OffsetMapping.dataStart(1024),
            file.length(),
            "占位文件应当已经含有完整头部 + 预留区",
        )
    }

    /** spec: import-commit-protocol「导入中断后重启」。 */
    @Test
    fun `crash at fifty percent of the data area leaves an uncommitted file`() {
        val file = File(dir, "crash-50.bin")
        val writer = writeSession(file)
        writer.writePlaceholder(3L * p)
        repeat(2) { writer.writeChunk(patternBytes(p), p) }
        writer.close()                       // 模拟进程被杀：没有 commit
        // 无密码判据（Task 8 会证明带密码时它是 UncommittedFileError 而不是「损坏」）
        assertEquals(ContainerClassifier.State.UNCOMMITTED, classify(file))
    }

    /**
     * design.md D4：commit 内部 MUST 先写 metadata 正文、再写 tag。
     * 写反会出现「tag 正确但正文仍是占位」的窗口：real_len 读出来 0 而 tag 校验通过，
     * 两套判据互相矛盾。这里直接钉住写入序列。
     */
    @Test
    fun `commit writes the metadata body before the tag`() {
        val probe = probeWrites(patternBytes(p + 1))
        val last = probe.last()
        assertEquals(OffsetMapping.metadataTagOffset(1024), last.offset, "最后一次写必须是 metadata_tag")
        assertEquals(ContainerConstants.METADATA_TAG_LEN, last.length)
        val body = probe[probe.size - 2]
        assertEquals(OffsetMapping.METADATA_OFFSET, body.offset, "倒数第二次写必须是 metadata 正文")
        assertEquals(1024, body.length)
    }

    /** spec: import-commit-protocol「写到一半被杀」——正文写完但 tag 未写。 */
    @Test
    fun `crash after the commit body leaves the file uncommitted, never corrupted`() {
        val plaintext = patternBytes(p + 1)
        val tagWriteIndex = probeWrites(plaintext).size - 1      // 最后一次写就是 tag
        val file = File(dir, "crash-in-commit.bin")
        val recording = RecordingChannel(
            FileContainerChannel.openForWrite(file),
            failAtWriteIndex = tagWriteIndex,
        )
        val writer = EncryptedFileWriter(recording, masterKey, 1024, DeterministicRandomSource(seed = 1))
        assertFailsWith<SimulatedCrashError> {
            writer.writeFrom(ByteArrayInputStream(plaintext), "crash.mp4", plaintext.size.toLong())
        }
        writer.close()
        // 磁盘上：正文已写、tag 那一次写没发生 → tag 仍是占位时的 16 个零
        val lastRecorded = recording.writes.last()
        assertEquals(OffsetMapping.METADATA_OFFSET, lastRecorded.offset, "上一次成功的写应当是 metadata 正文")
        assertEquals(1024, lastRecorded.length)
        assertEquals(ContainerClassifier.State.UNCOMMITTED, classify(file))
        // 「带密码时判为未提交而不是损坏」由 Task 8 的
        // `uncommitted file from a crash inside commit is not corrupted` 覆盖
    }

    /** spec: vault-crypto「主密钥不落盘」。 */
    @Test
    fun `master key bytes never appear in the written file`() {
        val (file, _) = writeFile(patternBytes(2 * p + 3))
        val cipher = file.readBytes()
        assertEquals(-1, indexOf(cipher, masterKey), "主密钥的 32 字节出现在磁盘上")
        // 收紧一次：把主密钥的十六进制文本也扫一遍（防日志/注释写进文件）
        val asText = String(cipher, Charsets.ISO_8859_1)
        assertTrue(!asText.contains(masterKey.joinToString("") { "%02x".format(it) }))
    }

    /** spec: import-commit-protocol「无密码读不出显示名」。 */
    @Test
    fun `display name never appears in the ciphertext file`() {
        val name = "我的私人视频-绝密.mp4"
        val (file, _) = writeFile(patternBytes(5000), name = name)
        val cipher = file.readBytes()
        assertEquals(-1, indexOf(cipher, name.toByteArray(Charsets.UTF_8)), "显示名以明文出现在密文中")
        assertEquals(-1, indexOf(file.readBytes(), "绝密".toByteArray(Charsets.UTF_8)))
    }

    /**
     * design.md D3：`metadata_len` 不是格式冻结项。写一个 4096 的文件，
     * 断言偏移确实按**头部里那个数**走。用 reader 解回来的那半在 Task 8。
     */
    @Test
    fun `a non default metadata length is written consistently`() {
        val plaintext = patternBytes(p + 9)
        val (file, _) = writeFile(plaintext, metadataLen = 4096)
        assertEquals(4096, readHeader(file).metadataLen)
        assertEquals(OffsetMapping.expectedFileSize(plaintext.size.toLong(), 4096), file.length())
        // data_start 随 metadata_len 平移：4096 比 1024 多 3072 字节。
        // 从预期文件大小里减掉数据区密文（p + 9 明文 → 两块，每块各带 16 字节 tag）
        // 剩下的就是 data_start。
        assertEquals(
            OffsetMapping.dataStart(1024) + 3072,
            OffsetMapping.expectedFileSize(plaintext.size.toLong(), 4096) - (p + 9 + 32),
        )
    }

    @Test
    fun `committing twice is refused`() {
        val file = File(dir, "twice.bin")
        val writer = writeSession(file)
        writer.writePlaceholder(4)
        writer.writeChunk(patternBytes(4), 4)
        writer.commit("a.mp4", 1L, 4L)
        assertFailsWith<IllegalStateException> { writer.commit("a.mp4", 1L, 4L) }
        writer.close()
    }

    @Test
    fun `writing a short chunk then another chunk is refused`() {
        val file = File(dir, "short-then-more.bin")
        val writer = writeSession(file)
        writer.writePlaceholder(2L * p)
        writer.writeChunk(patternBytes(17), 17)
        assertFailsWith<IllegalStateException> { writer.writeChunk(patternBytes(17), 17) }
        writer.close()
    }

    // --- 小工具 -------------------------------------------------------------

    /**
     * 先用同一个种子把一次完整写入的写入序列跑一遍，拿到它之后测试就能
     * 「在第 N 次写之后中断」，而不必把 N 硬编码在断言里。
     */
    private fun probeWrites(plaintext: ByteArray): List<RecordingChannel.WriteCall> {
        val file = File(dir, "probe-${plaintext.size}-${System.nanoTime()}.bin")
        val recording = RecordingChannel(FileContainerChannel.openForWrite(file))
        EncryptedFileWriter(recording, masterKey, 1024, DeterministicRandomSource(seed = 1)).use {
            it.writeFrom(ByteArrayInputStream(plaintext), "probe.mp4", plaintext.size.toLong(), 98_765L)
        }
        return recording.writes.toList()
    }

    private fun readHeader(file: File) =
        FileContainerChannel.openForRead(file).use { channel ->
            val bytes = ByteArray(ContainerConstants.FIXED_HEADER_LEN)
            channel.readAt(0L, bytes, bytes.size)
            FixedHeader.decode(bytes)
        }

    private fun classify(file: File): ContainerClassifier.State =
        FileContainerChannel.openForRead(file).use { ContainerClassifier.classify(it) }
}
