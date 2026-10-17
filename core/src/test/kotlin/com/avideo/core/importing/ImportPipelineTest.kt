package com.avideo.core.importing

import com.avideo.core.DeterministicRandomSource
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.MasterKeyDeriver
import com.avideo.core.patternBytes
import com.avideo.core.tempDir
import com.avideo.core.vault.VaultDirectory
import com.avideo.core.vault.VaultEntry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 导入管线。这一层是纯 JVM 可测的 —— 也正是把「写盘决策」全部放在 :core 的理由：
 * app 层没有 instrumentation 测试基建，而这里的每一条都是 spec 里写死的行为。
 */
class ImportPipelineTest {

    private val params = KdfParams.TEST
    private val password = "仓库密码".toCharArray()

    private fun newVault(): Triple<VaultDirectory, ByteArray, ImportPipeline> {
        val root = File(tempDir(), "vault")
        val directory = VaultDirectory(root)
        val meta = directory.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
        return Triple(directory, masterKey, ImportPipeline(directory, 1024, DeterministicRandomSource(9)))
    }

    /** 可控的导入源：能数被开了几次、能扮演各种失败。 */
    private class FakeSource(
        override val displayName: String,
        private val bytes: ByteArray,
        override val statedSize: Long? = bytes.size.toLong(),
        private val openError: Throwable? = null,
        private val cleanupResult: CleanupResult = CleanupResult.Hidden,
    ) : ImportSource {

        var openCount = 0
            private set
        var cleanupCount = 0
            private set

        override fun open(): InputStream {
            openCount++
            openError?.let { throw it }
            return ByteArrayInputStream(bytes)
        }

        override fun cleanup(): CleanupResult {
            cleanupCount++
            return cleanupResult
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun `a normal import commits the ciphertext and reports hidden`() {
        val (directory, masterKey, pipeline) = newVault()
        val bytes = patternBytes(5000)
        val source = FakeSource("我的视频.mp4", bytes)

        val outcome = pipeline.run(masterKey, listOf(ImportRequest(source))).single()

        val imported = assertIs<ImportOutcome.Imported>(outcome)
        assertEquals(5000L, imported.plaintextLength)
        assertTrue(imported.hidden, "cleanup 验证通过时必须是「已藏好」")
        assertEquals(null, imported.cleanupDetail)
        assertEquals(1, source.cleanupCount)

        val entry = assertIs<VaultEntry.Committed>(directory.list(masterKey).single())
        assertEquals("我的视频.mp4", entry.metadata.displayName)
        assertContentEquals(sha256(bytes), entry.metadata.sha256)
        assertEquals(1, directory.cipherFiles().size)
    }

    /** spec: media-source「声明大小不可信」—— 写进去的必须是实读值。 */
    @Test
    fun `a lying stated size does not change what is written`() {
        val (directory, masterKey, pipeline) = newVault()
        val source = FakeSource("谎报大小.mp4", patternBytes(3000), statedSize = 999_999L)

        val imported = assertIs<ImportOutcome.Imported>(
            pipeline.run(masterKey, listOf(ImportRequest(source))).single()
        )
        assertEquals(3000L, imported.plaintextLength, "plaintext_len 必须是实读字节数")
        assertEquals(3000L, directory.indexEntries(masterKey).single().plaintextLength, "文件头里的长度也必须是实读值")
    }

    /** 声明 0 字节 ⇒ 开流之前就拒绝，且 data/ 不新增文件（spec: import-flow）。 */
    @Test
    fun `a source declared as zero bytes is rejected before opening`() {
        val (directory, masterKey, pipeline) = newVault()
        val source = FakeSource("空.mp4", ByteArray(0), statedSize = 0L)

        val outcome = pipeline.run(masterKey, listOf(ImportRequest(source))).single()

        assertIs<ImportOutcome.Failed>(outcome)
        assertEquals(0, source.openCount, "0 字节必须在开流之前被拒绝")
        assertEquals(0, directory.cipherFiles().size)
    }

    /** 声明非 0、实际读出 0 ⇒ 失败，且 MUST NOT 留下密文文件。 */
    @Test
    fun `a source that turns out to be empty leaves no file behind`() {
        val (directory, masterKey, pipeline) = newVault()
        val source = FakeSource("其实是空的.mp4", ByteArray(0), statedSize = 4096L)

        val outcome = pipeline.run(masterKey, listOf(ImportRequest(source))).single()

        assertIs<ImportOutcome.Failed>(outcome)
        assertEquals(0, directory.cipherFiles().size, "未提交的半成品必须被删掉")
        assertTrue(directory.listUncommitted().isEmpty())
    }

    @Test
    fun `a read failure fails only that source and leaves nothing behind`() {
        val (directory, masterKey, pipeline) = newVault()
        val bad = FakeSource("读不了.mp4", patternBytes(100), openError = IOException("gone"))

        val outcome = pipeline.run(masterKey, listOf(ImportRequest(bad))).single()

        val failed = assertIs<ImportOutcome.Failed>(outcome)
        assertTrue(!failed.reason.contains("gone"), "原因里不得出现 provider 的原始消息：${failed.reason}")
        assertEquals(0, directory.cipherFiles().size)
        assertEquals(0, bad.cleanupCount, "写入失败时 MUST NOT 清理源文件")
    }

    /** spec: import-flow「批量导入的独立性与原子性」：一个失败不影响别人。 */
    @Test
    fun `one failure in a batch does not affect the others`() {
        val (directory, masterKey, pipeline) = newVault()
        val first = FakeSource("一.mp4", patternBytes(1000))
        val second = FakeSource("二.mp4", patternBytes(2000), openError = IOException("boom"))
        val third = FakeSource("三.mp4", patternBytes(3000))

        val outcomes = pipeline.run(
            masterKey,
            listOf(first, second, third).map { ImportRequest(it) },
        )

        assertEquals(3, outcomes.size)
        assertIs<ImportOutcome.Imported>(outcomes[0])
        assertIs<ImportOutcome.Failed>(outcomes[1])
        assertIs<ImportOutcome.Imported>(outcomes[2])
        assertEquals(2, directory.cipherFiles().size)
        assertEquals(
            listOf("一.mp4", "三.mp4"),
            directory.list(masterKey).filterIsInstance<VaultEntry.Committed>().map { it.metadata.displayName }.sorted(),
        )
    }

    /** spec: source-cleanup「密文写入成功、删除失败 ⇒ 尚未隐藏」。 */
    @Test
    fun `a failed cleanup still imports, but as not hidden`() {
        val (directory, masterKey, pipeline) = newVault()
        val source = FakeSource(
            "删不掉.mp4",
            patternBytes(1200),
            cleanupResult = CleanupResult.NotHidden("源 URI 仍可读"),
        )

        val imported = assertIs<ImportOutcome.Imported>(
            pipeline.run(masterKey, listOf(ImportRequest(source))).single()
        )
        assertTrue(!imported.hidden, "删除未验证 ⇒ MUST NOT 是「已藏好」")
        assertEquals("源 URI 仍可读", imported.cleanupDetail)
        assertEquals(1, directory.cipherFiles().size, "清理失败不影响密文")
    }

    /** 清理抛异常：仍然算导入成功（数据已在仓库里），状态按「尚未隐藏」保守处理。 */
    @Test
    fun `a throwing cleanup does not turn a committed import into a failure`() {
        val (directory, masterKey, pipeline) = newVault()
        val source = object : ImportSource {
            override val displayName = "清理崩了.mp4"
            override val statedSize: Long? = 900L
            override fun open(): InputStream = ByteArrayInputStream(patternBytes(900))
            override fun cleanup(): CleanupResult = throw IllegalStateException("provider 炸了")
        }

        val imported = assertIs<ImportOutcome.Imported>(
            pipeline.run(masterKey, listOf(ImportRequest(source))).single()
        )
        assertTrue(!imported.hidden)
        assertNotNull(imported.cleanupDetail)
        assertEquals(1, directory.cipherFiles().size)
    }

    // ---------- 重复检测 ----------

    @Test
    fun `duplicates are found by content, not by name`() {
        val (directory, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("原名.mp4", bytes))))

        // 同名不同内容 ⇒ 不是重复。长度特意与已有一条相同，否则长度闸门先挡掉，
        // 这条就测不到「哈希比较」本身。
        val sameNameDifferentContent = FakeSource("原名.mp4", ByteArray(4000) { 0x11 })
        assertTrue(pipeline.findDuplicates(masterKey, listOf(sameNameDifferentContent)).isEmpty())

        // 不同名同内容 ⇒ 是重复，且要指出对方是谁
        val differentNameSameContent = FakeSource("另一个名字.mp4", bytes)
        val match = pipeline.findDuplicates(masterKey, listOf(differentNameSameContent)).single()
        assertEquals("原名.mp4", match.match.existingName)
    }

    /**
     * 长度闸门：源声明的大小对不上任何条目 ⇒ 不需要读源文件。
     * 这条守的正是「MUST NOT 为了去重而额外完整读取源文件」。
     */
    @Test
    fun `dedup does not read the source when the size gate rejects it`() {
        val (directory, masterKey, pipeline) = newVault()
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("a.mp4", patternBytes(4000)))))

        val source = FakeSource("b.mp4", patternBytes(7000))
        assertTrue(pipeline.findDuplicates(masterKey, listOf(source)).isEmpty())
        assertEquals(0, source.openCount, "长度不同时不该读源文件")
    }

    /** 长度撞上时必须读一遍源才能判重 —— 这是无法回避的代价，明确钉住。 */
    @Test
    fun `dedup reads the source when the size gate matches`() {
        val (directory, masterKey, pipeline) = newVault()
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("a.mp4", patternBytes(4000)))))

        // 同长度、不同内容。用 patternBytes(4000) 会得到与 a.mp4 完全相同的字节（那就是真重复了）。
        val source = FakeSource("b.mp4", ByteArray(4000) { 0x22 })
        assertTrue(pipeline.findDuplicates(masterKey, listOf(source)).isEmpty(), "同长度不同内容不是重复")
        assertEquals(1, source.openCount, "长度撞上时必须读一遍才能判")
    }

    /** 正常导入（无重复）时源文件只被完整读取一次。 */
    @Test
    fun `a normal import reads the source exactly once`() {
        val (directory, masterKey, pipeline) = newVault()
        val source = FakeSource("单遍.mp4", patternBytes(4000))

        assertTrue(pipeline.findDuplicates(masterKey, listOf(source)).isEmpty())
        pipeline.run(masterKey, listOf(ImportRequest(source)))

        assertEquals(1, source.openCount, "导入路径 MUST 只完整读一遍源文件")
    }

    @Test
    fun `skip writes nothing`() {
        val (directory, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("a.mp4", bytes))))
        val before = directory.cipherFiles().map { it.name }

        val duplicateSource = FakeSource("b.mp4", bytes)
        val match = pipeline.findDuplicates(masterKey, listOf(duplicateSource)).single()
        val request = ImportRequest(match.source, match.match, DuplicateChoice.SKIP)
        val outcome = pipeline.run(masterKey, listOf(request)).single()

        assertIs<ImportOutcome.Skipped>(outcome)
        assertEquals(before, directory.cipherFiles().map { it.name }, "跳过时 data/ 不得新增文件")
        assertEquals(0, duplicateSource.cleanupCount, "跳过时 MUST NOT 删源文件")
    }

    @Test
    fun `replace leaves exactly one entry with the new name`() {
        val (directory, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("旧名字.mp4", bytes))))

        val match = pipeline.findDuplicates(masterKey, listOf(FakeSource("新名字.mp4", bytes))).single()
        val imported = assertIs<ImportOutcome.Imported>(
            pipeline.run(masterKey, listOf(ImportRequest(match.source, match.match, DuplicateChoice.REPLACE))).single()
        )

        val entries = directory.list(masterKey).map { it as VaultEntry.Committed }
        assertEquals(1, entries.size, "替换后只该剩一条")
        assertEquals("新名字.mp4", entries.single().metadata.displayName)
        assertTrue(!File(directory.dataDir, "${match.match.existingUuid}.bin").exists())
        assertEquals(imported.uuid, entries.single().uuid)
    }

    @Test
    fun `keep both leaves two entries`() {
        val (directory, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("一.mp4", bytes))))

        val match = pipeline.findDuplicates(masterKey, listOf(FakeSource("二.mp4", bytes))).single()
        pipeline.run(masterKey, listOf(ImportRequest(match.source, match.match, DuplicateChoice.KEEP_BOTH)))

        assertEquals(2, directory.list(masterKey).size)
        assertEquals(
            listOf("一.mp4", "二.mp4"),
            directory.list(masterKey).map { (it as VaultEntry.Committed).metadata.displayName }.sorted(),
        )
    }

    @Test
    fun `the index skips uncommitted and corrupted entries`() {
        val (directory, masterKey, pipeline) = newVault()
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("好的.mp4", patternBytes(1000)))))
        // 损坏条目：随便扔一个文件进 data/
        File(directory.dataDir, "11111111-1111-4111-8111-111111111111.bin").writeBytes(patternBytes(400))
        // 未提交条目：写占位就停
        val orphan = directory.newWriter("22222222-2222-4222-8222-222222222222", masterKey, 1024)
        orphan.writePlaceholder(5000L)
        orphan.close()

        val index = directory.indexEntries(masterKey)

        assertEquals(1, index.size, "只该索引到那一条已提交的：$index")
        assertEquals("好的.mp4", index.single().displayName)
        assertEquals(1000L, index.single().plaintextLength)
        assertContentEquals(sha256(patternBytes(1000)), index.single().sha256)
    }
}
