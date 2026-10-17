package com.avideo.core.importing

import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.crypto.RandomSource
import com.avideo.core.errors.EmptySourceError
import com.avideo.core.errors.VaultError
import com.avideo.core.vault.VaultDirectory
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** 一条待导入项。[duplicate] / [choice] 由预检 + 用户裁决填入；非重复时都为 null。 */
data class ImportRequest(
    val source: ImportSource,
    val duplicate: DedupMatch? = null,
    val choice: DuplicateChoice? = null,
) {
    init {
        require((duplicate == null) == (choice == null)) {
            "duplicate 与 choice 必须同时给出或同时为空"
        }
    }
}

/** 预检判定出的一条重复，等用户裁决。 */
data class DuplicateCandidate(val source: ImportSource, val match: DedupMatch)

/** 单个源的结局。MUST 逐个报告，一个失败不影响同批次其他源（spec: import-flow）。 */
sealed interface ImportOutcome {

    val label: String

    /**
     * 密文已提交。
     *
     * [hidden] 为 true 当且仅当源文件**删除并验证**通过 —— 这是「已藏好」的唯一路径
     * （spec: source-cleanup「已藏好只有一条路径可达」）。
     * [cleanupDetail] 只在 [hidden] 为 false 时有值，且只含结构性信息。
     */
    data class Imported(
        override val label: String,
        val uuid: String,
        val displayName: String,
        /** 实读字节数，不是 provider 声明的大小。 */
        val plaintextLength: Long,
        val hidden: Boolean,
        val cleanupDetail: String?,
    ) : ImportOutcome

    /** 没写盘。目前只有两种来源：用户对重复项选了「跳过」，或声明大小为 0。 */
    data class Skipped(override val label: String, val reason: String) : ImportOutcome

    /** 失败。密文 MUST NOT 留在仓库里（未提交的半成品会被删掉）。 */
    data class Failed(override val label: String, val reason: String) : ImportOutcome
}

/** 进度：只用于界面上的进度条，不含任何明文字节。 */
data class ImportProgress(
    val index: Int,
    val total: Int,
    val label: String,
    val bytesRead: Long,
    /** provider 声明的大小；未知时 null。 */
    val statedSize: Long?,
)

/**
 * 导入管线：**唯一**的加密写盘实现（spec: import-flow「MUST NOT 存在第二份」）。
 *
 * 逐条串行处理，理由：手机上的瓶颈是存储带宽，并发写入只会互相抢 I/O；
 * 而 spec 要求的「独立性」是**失败隔离**，不是并发。
 */
class ImportPipeline(
    private val directory: VaultDirectory,
    private val metadataLen: Int = ContainerConstants.METADATA_LEN,
    private val random: RandomSource = RandomSource.secure(),
) {

    /**
     * 预检重复（spec: duplicate-detection「跳过不在写盘之后发生」）。
     *
     * 做法是**长度闸门 + 必要时才哈希**：
     *  - 库内每条的长度从固定头读，代价为零；
     *  - 源声明的大小对不上任何条目 ⇒ 必然不是重复，**不读源文件**；
     *  - 只有长度撞上（或 provider 没给大小）才完整读一遍源算 SHA-256。
     *
     * 这样绝大多数导入仍然满足「源文件只被完整读取一次」；
     * 而长度相同的两条大视频本来就要比内容，多读一遍是这类判定无法回避的代价。
     * （设计文档 D6 同时要求「跳过时不写盘」与「只读一遍」，两者在长度撞车时不可兼得，
     * 这里选前者 —— 白写一份 2 GB 密文比多读一遍 2 GB 更贵。）
     *
     * 读不出哈希的源不进结果：让写入阶段去报它真正的错误（那里有完整的错误分类）。
     */
    fun findDuplicates(masterKey: ByteArray, sources: List<ImportSource>): List<DuplicateCandidate> {
        val index = directory.indexEntries(masterKey)
        // 同一批里也可能有两份内容相同的文件（用户一次选了两个副本）。
        // 它们互相之间同样要判重，只是「重复对象」不是库内条目 —— 用 inBatch 标记。
        val batchSizeCounts = sources.mapNotNull { it.statedSize }.groupingBy { it }.eachCount()
        val seenInBatch = HashMap<String, ImportSource>()

        return sources.mapNotNull { source ->
            val size = source.statedSize
            val vaultCandidates = if (size == null) index else index.filter { it.plaintextLength == size }
            // 需要哈希的两种情况：库内长度撞车，或同批里有另一个同长度的源。
            val sharesSizeInBatch = if (size == null) {
                sources.size > 1                      // 声明大小未知：只能保守地算一遍
            } else {
                (batchSizeCounts[size] ?: 0) > 1
            }
            if (vaultCandidates.isEmpty() && !sharesSizeInBatch) return@mapNotNull null

            val sha = try {
                sha256Of(source)
            } catch (e: IOException) {
                return@mapNotNull null
            } catch (e: VaultError) {
                return@mapNotNull null
            }
            val shaKey = sha.joinToString("") { "%02x".format(it) }
            val previousInBatch = seenInBatch.put(shaKey, source)

            // 库内优先：与已有条目重复时，提示里要指出的是库里那一条。
            index.firstOrNull { it.sha256.contentEquals(sha) }?.let { hit ->
                return@mapNotNull DuplicateCandidate(
                    source,
                    DedupMatch(existingUuid = hit.uuid, existingName = hit.displayName, inBatch = false),
                )
            }
            previousInBatch?.let { earlier ->
                return@mapNotNull DuplicateCandidate(
                    source,
                    DedupMatch(existingUuid = "", existingName = earlier.displayName, inBatch = true),
                )
            }
            null
        }
    }

    /**
     * 逐条导入。[onOutcome] 每完成一条就被调用一次（UI 靠它逐条显示结果）。
     *
     * @return 与 [requests] 一一对应的结局，顺序相同。
     */
    fun run(
        masterKey: ByteArray,
        requests: List<ImportRequest>,
        onProgress: (ImportProgress) -> Unit = {},
        onOutcome: (ImportOutcome) -> Unit = {},
    ): List<ImportOutcome> {
        require(masterKey.size == ContainerConstants.KEYS_LEN) {
            "master key must be ${ContainerConstants.KEYS_LEN} bytes"
        }
        return requests.mapIndexed { index, request ->
            runOne(masterKey, request, index, requests.size, onProgress).also(onOutcome)
        }
    }

    private fun runOne(
        masterKey: ByteArray,
        request: ImportRequest,
        index: Int,
        total: Int,
        onProgress: (ImportProgress) -> Unit,
    ): ImportOutcome {
        val source = request.source
        val label = source.label

        // ① 用户选了跳过 ⇒ 一个字节都不写（spec: duplicate-detection）。
        //    同批重复即使误传 REPLACE 也按 SKIP 处理：没有旧条目可删，删错就是数据丢失。
        val inBatchReplace = request.choice == DuplicateChoice.REPLACE &&
            request.duplicate?.inBatch == true
        if (request.choice == DuplicateChoice.SKIP || inBatchReplace) {
            val who = request.duplicate?.existingName
            val scope = if (request.duplicate?.inBatch == true) "同一批里的" else "已有条目"
            return ImportOutcome.Skipped(label, "与$scope「$who」内容相同，已跳过")
        }
        // ② 声明为 0 字节 ⇒ 开流之前就拒绝（spec: import-flow）
        if (source.statedSize == 0L) {
            return ImportOutcome.Failed(label, "源文件是空的（0 字节），没有导入")
        }

        val uuid = UUID.randomUUID().toString()
        var writer: EncryptedFileWriter? = null
        var committed = false
        try {
            writer = directory.newWriter(uuid, masterKey, metadataLen, random)
            val stream = CountingInputStream(
                delegate = source.open(),
                onBytes = { read ->
                    onProgress(ImportProgress(index, total, label, read, source.statedSize))
                },
            )
            val actual = try {
                // declaredPlaintextLen 只是提示；commit 会按实读值重写 plaintext_len（stage-0 D-2）。
                // 时长一律未知：取时长要 MediaMetadataRetriever，它在某些容器上会把文件再读一遍，
                // 与「单遍读取」冲突。列表页会把未知显示成「时长未知」。
                writer.writeFrom(
                    source = stream,
                    displayName = source.displayName,
                    declaredPlaintextLen = source.statedSize ?: 0L,
                    durationMs = ContainerConstants.DURATION_UNKNOWN,
                )
            } finally {
                stream.close()
            }
            // 实读字节数才是写进 plaintext_len 的值 —— provider 声明的大小不可信。
            val actualLength = stream.count
            committed = true

            // ③ 提交成功后才谈清理。清理抛异常也必须落成「尚未隐藏」，不能变成「导入失败」
            //    —— 数据已经在仓库里了，把它报成失败会让用户以为可以重来。
            val cleanup = try {
                source.cleanup()
            } catch (e: Exception) {
                CleanupResult.NotHidden("cleanup 抛出 ${e.javaClass.simpleName}")
            }

            // ④ 「替换」：先写成功再删旧的。顺序反了的话，写失败就白丢一条。
            var replaceNote: String? = null
            if (request.choice == DuplicateChoice.REPLACE) {
                val oldUuid = request.duplicate?.existingUuid
                if (oldUuid == null || !directory.deleteEntry(oldUuid)) {
                    replaceNote = "旧条目没能删掉（替换未完成）"
                }
            }

            val detail = when (cleanup) {
                CleanupResult.Hidden -> replaceNote
                is CleanupResult.NotHidden -> listOfNotNull(cleanup.detail, replaceNote).joinToString("；")
            }
            return ImportOutcome.Imported(
                label = label,
                uuid = uuid,
                displayName = actual.displayName,
                plaintextLength = actualLength,
                hidden = cleanup is CleanupResult.Hidden,
                cleanupDetail = detail,
            )
        } catch (e: EmptySourceError) {
            return ImportOutcome.Failed(label, "源文件是空的（只读到 0 字节），没有导入")
        } catch (e: VaultError) {
            // 只带类型名：VaultError 的 message 在设计上只含结构性信息，但类型名更窄。
            return ImportOutcome.Failed(label, "写入失败（${e.javaClass.simpleName}）")
        } catch (e: IOException) {
            return ImportOutcome.Failed(label, "读取或写入失败（I/O）")
        } catch (e: SecurityException) {
            // SAF 授权过期、或 provider 拒读
            return ImportOutcome.Failed(label, "没有读取该文件的权限")
        } finally {
            // 先关 writer（它负责清零 data key），再考虑删半成品
            runCatching { writer?.close() }
            if (!committed) directory.deleteEntry(uuid)
        }
    }

    private fun sha256Of(source: ImportSource): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        source.open().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    /** 给进度用的计数流；同时提供「实读字节数」，它才是写进 `plaintext_len` 的值。 */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {

        var count: Long = 0L
            private set

        override fun read(): Int = delegate.read().let { value ->
            if (value >= 0) {
                count += 1
                onBytes(count)
            }
            value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).let { n ->
            if (n > 0) {
                count += n
                onBytes(count)
            }
            n
        }

        override fun close() = delegate.close()
    }
}
