package com.avideo.core.container

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.RecordingChannel
import com.avideo.core.SimulatedCrashError
import com.avideo.core.crypto.KeyWrapping
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

    /**
     * **SAF / `ContentResolver` 的流被明确允许返回短读**，所以「一次 `read` 读不满就是 EOF」
     * 是错的：`fill` 必须循环到缓冲区满或真的撞到 EOF。
     *
     * 这条正是那个退化的守门测试。若 `fill` 退化成单次 `read`，`writeFrom` 会把第一次短读
     * 当成最后一块、`commit` 出一个**完全合法、内容却被截断**的容器 —— 不报错、不置损坏标志、
     * 只是少了内容。所以这里既断言头部记的 `plaintext_len`、也断言字节级往返。
     */
    @Test
    fun `a source that returns short reads is not mistaken for EOF`() {
        val plaintext = patternBytes(2 * p + 123)          // 跨 3 块，强制出现多次部分读
        val maxPerRead = 4096                             // 远小于块长：必然产生大量短读
        var eofQueries = 0
        var delivered = 0L
        val chunked = object : InputStream() {
            private val delegate = ByteArrayInputStream(plaintext)
            override fun read(): Int = delegate.read().also { if (it < 0) eofQueries++ else delivered++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = delegate.read(b, off, minOf(len, maxPerRead))
                if (n < 0) eofQueries++ else delivered += n
                return n
            }
        }

        val file = File(dir, "short-reads.bin")
        val metadata = writeSession(file).use {
            it.writeFrom(chunked, "短读.mp4", plaintext.size.toLong())
        }

        assertEquals(
            plaintext.size.toLong(),
            readHeader(file).plaintextLen,
            "头部记的长度是截断后的长度：短读被当成了 EOF",
        )
        assertEquals(OffsetMapping.expectedFileSize(plaintext.size.toLong(), 1024), file.length())
        // 源流仍只被完整读一遍：恰好问到一次 EOF，交付的字节数正好是明文长度
        assertEquals(1, eofQueries, "源流被反复重读（EOF 被问了 $eofQueries 次）")
        assertEquals(plaintext.size.toLong(), delivered)
        assertContentEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(plaintext),
            metadata.sha256,
        )
        // 字节级往返：截断的话这里会短一截
        EncryptedFileReader.open(FileContainerChannel.openForRead(file), masterKey).use { reader ->
            assertContentEquals(plaintext, reader.read(0L, plaintext.size))
        }
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

    /**
     * commit 重写头部后，**重算过的 header_tag 必须能用该文件的 data key 验证通过**。
     * 这是唯一能在 writer 侧证明「修正分支没有退回占位头」的检查：
     * 退回的话 header_tag 是用旧头字节算的，这里就会验不过。
     */
    @Test
    fun `the rewritten header authenticates with its own data key`() {
        val plaintext = patternBytes(p + 5)
        val file = File(dir, "rewrite-tag.bin")
        EncryptedFileWriter(
            FileContainerChannel.openForWrite(file),
            masterKey,
            ContainerConstants.METADATA_LEN,
            DeterministicRandomSource(seed = 1),
        ).use {
            it.writeFrom(ByteArrayInputStream(plaintext), "被修正.mp4", 12L)   // 声明值与实读值不同
        }

        val headerBytes = ByteArray(ContainerConstants.FIXED_HEADER_LEN)
        FileContainerChannel.openForRead(file).use { channel ->
            assertEquals(headerBytes.size, channel.readAt(0L, headerBytes, headerBytes.size))
            val header = FixedHeader.decode(headerBytes)
            assertEquals(plaintext.size.toLong(), header.plaintextLen, "头部没有按实读长度修正")

            val tag = ByteArray(ContainerConstants.HEADER_TAG_LEN)
            assertEquals(
                tag.size,
                channel.readAt(OffsetMapping.HEADER_TAG_OFFSET, tag, tag.size),
            )

            // 用主密钥解出该文件的 data key，再用它验头部 tag
            val dataKey = KeyWrapping.unwrap(masterKey, header.wrappedFileKey)
            try {
                Aead.verifyTagOnly(dataKey, Nonce.header(header.baseNonce), headerBytes, tag)
            } finally {
                java.util.Arrays.fill(dataKey, 0)
            }
        }
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

    /**
     * **A6：正文与 tag 之间 MUST 有一次落盘屏障。**
     * `force(false)`（即 [ContainerChannel.flush]）不保证页的写回顺序，断电可能让 tag 先持久化
     * —— 那时 tag 非零（读者判「已提交」）而正文还是占位/半个，`real_len` 读出 0。
     * 屏障**不**改变提交点：提交点仍然是写 tag 那一次写，屏障只是保证 tag 落盘前正文已经落盘。
     * 顺序必须是：正文 → flush → tag → flush。
     */
    @Test
    fun `commit flushes the metadata body before writing the tag`() {
        val plaintext = patternBytes(p + 1)
        val file = File(dir, "flush-barrier.bin")
        val recording = RecordingChannel(FileContainerChannel.openForWrite(file))
        EncryptedFileWriter(recording, masterKey, 1024, DeterministicRandomSource(seed = 1)).use {
            it.writeFrom(ByteArrayInputStream(plaintext), "flush.mp4", plaintext.size.toLong())
        }

        val tail = recording.events.takeLast(4)
        assertEquals(4, tail.size, "写入序列比预期短：$tail")
        assertEquals(
            RecordingChannel.Event.Write(RecordingChannel.WriteCall(OffsetMapping.METADATA_OFFSET, 1024)),
            tail[0],
            "倒数第 4 次事件应当是 metadata 正文",
        )
        assertEquals(
            RecordingChannel.Event.Flush,
            tail[1],
            "写 tag 之前没有落盘屏障：断电可能让非零 tag 先于正文持久化",
        )
        assertEquals(
            RecordingChannel.Event.Write(
                RecordingChannel.WriteCall(OffsetMapping.metadataTagOffset(1024), ContainerConstants.METADATA_TAG_LEN),
            ),
            tail[2],
        )
        assertEquals(RecordingChannel.Event.Flush, tail[3])
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
     * design.md D3：`metadata_len` 不是格式冻结项。这里用**非 1024** 的预留区写出一个文件，
     * 断言落盘尺寸、头部里记的数、以及数据区随预留区平移的量三者一致。
     * 三条断言都落在 writer 的实际输出上，任何一条都不会因为「只算纯函数」而恒真。
     */
    @Test
    fun `a non default metadata length is written consistently`() {
        val len = 4096
        val plaintext = patternBytes(p + 9)          // 跨 2 块，尾块变长
        val (file, _) = writeFile(plaintext, metadataLen = len)
        assertEquals(len, readHeader(file).metadataLen)
        assertEquals(OffsetMapping.expectedFileSize(plaintext.size.toLong(), len), file.length())
        // data_start 随 metadata_len 平移：4096 比 1024 多 3072 字节。
        assertEquals(3072, OffsetMapping.dataStart(len) - OffsetMapping.dataStart(1024))
        // 用 reader 解回来的那半在 Task 8 的同一测试名下
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

    /**
     * 预留区必须落在 [METADATA_FIXED_FIELDS_LEN, MAX_METADATA_LEN]：
     * 太小装不下固定字段、太大超过头部能表达且自己分类为 MALFORMED 的上界。
     * 两者都 MUST 在构造时就拒绝，而不是等到写入阶段才炸。
     */
    @Test
    fun `metadata length outside the supported range is refused at construction`() {
        val small = FileContainerChannel.openForWrite(File(dir, "metadata-too-small.bin"))
        try {
            assertFailsWith<IllegalArgumentException> {
                EncryptedFileWriter(
                    small,
                    masterKey,
                    ContainerConstants.METADATA_FIXED_FIELDS_LEN - 1,
                    DeterministicRandomSource(seed = 1),
                )
            }
        } finally {
            small.close()
        }

        val large = FileContainerChannel.openForWrite(File(dir, "metadata-too-large.bin"))
        try {
            assertFailsWith<IllegalArgumentException> {
                EncryptedFileWriter(
                    large,
                    masterKey,
                    FixedHeader.MAX_METADATA_LEN + 1,
                    DeterministicRandomSource(seed = 1),
                )
            }
        } finally {
            large.close()
        }
    }

    /**
     * **M-1：`MAX_METADATA_LEN` 不得超过两个 16 位字段能表达的范围。**
     * `real_len` 与 `name_len` 各占 2 字节（`FileMetadata.encode` 用 `toShort()` 写），
     * 预留区开到 65536 以上时，长显示名会在这两个字段处**回绕**，产出一个
     * 「自洽但错误」的 payload —— 不报错，只是名字与长度已经是别的东西。
     *
     * 因此上界必须正好是 `0xFFFF`，且**最大预留区必须能原样往返一个最长显示名**
     * （65535 − 45 = 65490 字节）。这条断言是「上界取值正确」的唯一端到端证明。
     */
    @Test
    fun `the largest accepted metadata reserve round trips the longest display name`() {
        assertEquals(0xFFFF, FixedHeader.MAX_METADATA_LEN, "上界必须是 16 位字段能表达的最大值")

        val reserve = FixedHeader.MAX_METADATA_LEN
        val longestName = "n".repeat(reserve)          // 一定超过 maxDisplayNameBytes(reserve)
        val file = File(dir, "max-reserve.bin")
        EncryptedFileWriter(
            FileContainerChannel.openForWrite(file),
            masterKey,
            reserve,
            DeterministicRandomSource(seed = 1),
        ).use { it.writeFrom(ByteArrayInputStream(patternBytes(4096)), longestName, 4096L) }

        assertEquals(reserve, readHeader(file).metadataLen)
        EncryptedFileReader.open(FileContainerChannel.openForRead(file), masterKey).use { reader ->
            assertEquals(
                ContainerConstants.maxDisplayNameBytes(reserve),
                reader.metadata.displayName.toByteArray(Charsets.UTF_8).size,
                "最长显示名没有原样往返（real_len/name_len 在 16 位字段处回绕了）",
            )
            assertContentEquals(patternBytes(4096), reader.read(0L, 4096))
        }
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
