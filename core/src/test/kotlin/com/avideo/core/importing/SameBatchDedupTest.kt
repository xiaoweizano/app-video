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
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 同批判重 —— 一次选中两份内容相同的文件时，第二份也要被判为重复。
 *
 * 这是 2026-10-17 补的缺口：原来只对照「导入前库内已有的条目」，
 * 所以同一批里的两个副本会双双入库，白占一份空间。
 */
class SameBatchDedupTest {

    private val params = KdfParams.TEST
    private val password = "仓库密码".toCharArray()

    private class FakeSource(
        override val displayName: String,
        private val bytes: ByteArray,
        override val statedSize: Long? = bytes.size.toLong(),
    ) : ImportSource {
        var openCount = 0
            private set

        override fun open(): InputStream {
            openCount++
            return ByteArrayInputStream(bytes)
        }

        override fun cleanup(): CleanupResult = CleanupResult.Hidden
    }

    private fun newVault(): Triple<VaultDirectory, ByteArray, ImportPipeline> {
        val root = File(tempDir(), "vault")
        val directory = VaultDirectory(root)
        val meta = directory.create(params, DeterministicRandomSource(5))
        val masterKey = MasterKeyDeriver.forJvmOnly().derive(password, meta.kdfSalt, meta.kdfParams)
        return Triple(directory, masterKey, ImportPipeline(directory, 1024, DeterministicRandomSource(9)))
    }

    @Test
    fun `the second identical source in a batch is flagged as an in-batch duplicate`() {
        val (_, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        val first = FakeSource("副本 A.mp4", bytes)
        val second = FakeSource("副本 B.mp4", bytes)

        val candidates = pipeline.findDuplicates(masterKey, listOf(first, second))

        val only = candidates.single()
        assertTrue(only.source === second, "被标记的应当是后出现的那一份")
        assertTrue(only.match.inBatch, "同批重复必须标 inBatch")
        assertEquals("", only.match.existingUuid, "同批重复没有 uuid")
        assertEquals("副本 A.mp4", only.match.existingName, "要指出与谁重复")
    }

    /** 同长度但内容不同：需要哈希，但不得误判。 */
    @Test
    fun `same size but different content in a batch is not a duplicate`() {
        val (_, masterKey, pipeline) = newVault()
        val first = FakeSource("甲.mp4", ByteArray(4000) { 0x11 })
        val second = FakeSource("乙.mp4", ByteArray(4000) { 0x22 })

        assertTrue(pipeline.findDuplicates(masterKey, listOf(first, second)).isEmpty())
        assertEquals(1, first.openCount, "同长度必须算一遍哈希")
        assertEquals(1, second.openCount)
    }

    /** 库里已有同内容时，提示指向**库里的那条**，而不是同批的那条。 */
    @Test
    fun `a vault duplicate takes precedence over an in-batch one`() {
        val (_, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        pipeline.run(masterKey, listOf(ImportRequest(FakeSource("库里的.mp4", bytes))))

        val a = FakeSource("新 A.mp4", bytes)
        val b = FakeSource("新 B.mp4", bytes)
        val candidates = pipeline.findDuplicates(masterKey, listOf(a, b))

        assertEquals(2, candidates.size)
        assertTrue(
            candidates.all { !it.match.inBatch && it.match.existingName == "库里的.mp4" },
            "库内命中优先：$candidates",
        )
    }

    @Test
    fun `skip on an in-batch duplicate writes only one entry`() {
        val (directory, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        val first = FakeSource("副本 A.mp4", bytes)
        val second = FakeSource("副本 B.mp4", bytes)

        val match = pipeline.findDuplicates(masterKey, listOf(first, second)).single()
        val outcomes = pipeline.run(
            masterKey,
            listOf(
                ImportRequest(first),
                ImportRequest(match.source, match.match, DuplicateChoice.SKIP),
            ),
        )

        assertIs<ImportOutcome.Imported>(outcomes[0])
        assertIs<ImportOutcome.Skipped>(outcomes[1])
        val entry = assertIs<VaultEntry.Committed>(directory.list(masterKey).single())
        assertEquals("副本 A.mp4", entry.metadata.displayName)
    }

    /**
     * 同批重复上的 REPLACE 必须按 SKIP 处理：那时没有「旧条目」可删，
     * 真按替换去做就会删掉另一条用户刚选的文件。
     */
    @Test
    fun `replace on an in-batch duplicate degrades to skip`() {
        val (directory, masterKey, pipeline) = newVault()
        val bytes = patternBytes(4000)
        val first = FakeSource("副本 A.mp4", bytes)
        val second = FakeSource("副本 B.mp4", bytes)

        val match = pipeline.findDuplicates(masterKey, listOf(first, second)).single()
        val outcomes = pipeline.run(
            masterKey,
            listOf(
                ImportRequest(first),
                ImportRequest(match.source, match.match, DuplicateChoice.REPLACE),
            ),
        )

        assertIs<ImportOutcome.Imported>(outcomes[0])
        assertIs<ImportOutcome.Skipped>(outcomes[1])
        assertEquals(1, directory.cipherFiles().size, "REPLACE 不得删掉同批的另一份")
    }

    /** 单批只有一个源时，绝不因为「同批」而多读一遍。 */
    @Test
    fun `a single source never triggers the batch hash`() {
        val (_, masterKey, pipeline) = newVault()
        val source = FakeSource("单个.mp4", patternBytes(4000))

        assertTrue(pipeline.findDuplicates(masterKey, listOf(source)).isEmpty())
        assertEquals(0, source.openCount, "空仓库 + 单个源：不该读")
    }
}
