package com.avideo.core.backup

import com.avideo.core.container.ContainerClassifier
import com.avideo.core.container.EncryptedFileReader
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.vault.VaultDirectory
import com.avideo.core.vault.VaultMetaIO
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整个仓库的归档（`.avault.zip`）—— 卸载前唯一的逃生通道。
 *
 * 内容就是**原样的密文**：`vault.meta`、`vault.meta.bak`、`data/<uuid>.bin`。
 * 因此恢复后仍然只认原来的密码；归档本身不含任何密钥，也不需要恢复码
 * （spec: vault-restore「恢复 MUST NOT 需要恢复码」）。
 *
 * 用 zip 的 **STORE（不压缩）**：内容已经是密文，压缩没有收益，只浪费 CPU。
 */
object VaultArchive {

    const val META = "vault.meta"
    const val META_BAK = "vault.meta.bak"
    const val DATA_DIR = "data/"

    /** 归档文件名后缀。 */
    const val SUFFIX = ".avault.zip"

    /** 恢复时抽样校验的条目数上限（spec: 恢复后 SHOULD 抽样校验头部）。 */
    private const val SAMPLE_MAX = 5

    data class WriteReport(val entries: Int, val bytes: Long, val skippedUncommitted: Int)

    sealed interface RestoreResult {
        /**
         * [usedBackupMeta]：主文件不可用、用了 `.bak`。
         * [sampled] / [sampleFailures]：带密钥时抽样解密的结果；不带密钥时都为 0。
         */
        data class Success(
            val entries: Int,
            val usedBackupMeta: Boolean,
            val sampled: Int,
            val sampleFailures: Int,
        ) : RestoreResult

        /** [reason] 只许含结构性信息（缺什么、哪一步失败），不含路径中的用户内容。 */
        data class Failed(val reason: String) : RestoreResult
    }

    /**
     * 把仓库写成归档。**只包含已提交的条目**：未提交的半成品与结构损坏的文件都不进去
     * （spec: vault-export「不含未提交文件」）。后者会被计入 [WriteReport.skippedUncommitted]。
     */
    fun write(vaultDir: File, out: OutputStream): WriteReport {
        val directory = VaultDirectory(vaultDir)
        if (!directory.metaFile.isFile && !directory.backupFile.isFile) {
            throw IOException("vault.meta 与 vault.meta.bak 都不存在")
        }

        val files = mutableListOf<Pair<String, File>>()
        if (directory.metaFile.isFile) files += META to directory.metaFile
        if (directory.backupFile.isFile) files += META_BAK to directory.backupFile

        var skipped = 0
        directory.cipherFiles().forEach { file ->
            val committed = FileContainerChannel.openForRead(file).use { channel ->
                ContainerClassifier.classify(channel) == ContainerClassifier.State.COMMITTED
            }
            if (committed) files += (DATA_DIR + file.name) to file else skipped++
        }

        var total = 0L
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, file) ->
                // STORE 要求头部先给出 size 与 crc，所以每个文件读两遍：先算 crc，再写入。
                // 这是本地磁盘读，代价可接受；换来的是「不压缩」这条明文要求。
                val length = file.length()
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = length
                    compressedSize = length
                    crc = crc32Of(file)
                }
                zip.putNextEntry(entry)
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                total += length
            }
        }
        return WriteReport(entries = files.size, bytes = total, skippedUncommitted = skipped)
    }

    /**
     * 从归档恢复。**先解到临时目录、校验通过后才就位** —— 中途失败不会留下半个仓库，
     * 也不会破坏已有仓库（spec: vault-restore「恢复过程中的失败不留半成品」）。
     *
     * @param allowOverwrite 目标已有内容时是否允许覆盖。调用方 MUST 先问过用户。
     * @param masterKey 可为空。给了就抽样解密若干条目的头部（带密钥的校验）；
     *   仓库打不开时没有密钥，只能做无密钥校验 —— 那也够判断「归档结构对不对」。
     */
    fun restore(
        archive: InputStream,
        targetDir: File,
        allowOverwrite: Boolean,
        masterKey: ByteArray? = null,
    ): RestoreResult {
        val parent = targetDir.absoluteFile.parentFile
            ?: return RestoreResult.Failed("目标目录不可用")
        if (targetDir.exists() && !allowOverwrite) {
            val nonEmpty = targetDir.listFiles()?.isNotEmpty() == true
            if (nonEmpty) return RestoreResult.Failed("目标位置已有仓库，未做任何改动")
        }

        val stamp = System.currentTimeMillis()
        val temp = File(parent, "${targetDir.name}.restore-$stamp")
        var moved = false
        try {
            temp.mkdirs()
            var entries = 0
            val buffer = ByteArray(64 * 1024)

            ZipInputStream(BufferedInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val destination = when {
                        name == META -> File(temp, META)
                        name == META_BAK -> File(temp, META_BAK)
                        name.startsWith(DATA_DIR) -> {
                            // 用 VaultDirectory.cipherFile 校验 uuid 形状 —— 这样
                            // `data/../x.bin` 这类路径穿越在**落盘之前**就被拒绝，
                            // 不依赖任何字符串清洗。
                            val uuid = name.removePrefix(DATA_DIR).removeSuffix(".bin")
                            val probe = try {
                                VaultDirectory(temp).cipherFile(uuid)
                            } catch (e: IllegalArgumentException) {
                                return RestoreResult.Failed("归档里有一个不合法的条目名")
                            }
                            probe
                        }
                        else -> return RestoreResult.Failed("归档里有不认识的条目")
                    }
                    destination.parentFile?.mkdirs()
                    var written = 0L
                    destination.outputStream().use { sink ->
                        var read = zip.read(buffer)
                        while (read >= 0) {
                            sink.write(buffer, 0, read)
                            written += read
                            read = zip.read(buffer)
                        }
                    }
                    // 头部声明了长度就必须对得上：截断的归档 MUST 被拒绝，而不是落成
                    // 一个「短了几兆、但看起来正常」的仓库。
                    if (entry.size >= 0 && written != entry.size) {
                        return RestoreResult.Failed("归档被截断（条目长度不符）")
                    }
                    if (name != META && name != META_BAK) entries++
                }
            }

            if (!File(temp, META).isFile && !File(temp, META_BAK).isFile) {
                return RestoreResult.Failed("归档里缺少 vault.meta 与 vault.meta.bak")
            }
            val metaState = VaultMetaIO.load(temp)
            if (metaState is VaultMetaIO.LoadResult.Corrupted) {
                return RestoreResult.Failed("归档里的 vault.meta 与 vault.meta.bak 都校验不过")
            }
            val usedBackupMeta = metaState is VaultMetaIO.LoadResult.Ok && metaState.fromBackup

            // 就位：先把旧仓库挪开，再放新的；任一步失败都回滚。
            val old = if (targetDir.exists()) File(parent, "${targetDir.name}.old-$stamp") else null
            if (old != null && !targetDir.renameTo(old)) {
                return RestoreResult.Failed("无法移动现有仓库（未做改动）")
            }
            if (!temp.renameTo(targetDir)) {
                old?.renameTo(targetDir)
                return RestoreResult.Failed("无法把恢复结果就位，已回滚")
            }
            moved = true
            old?.deleteRecursively()

            var sampled = 0
            var sampleFailures = 0
            if (masterKey != null) {
                val restored = VaultDirectory(targetDir)
                restored.cipherFiles().take(SAMPLE_MAX).forEach { file ->
                    sampled++
                    runCatching {
                        EncryptedFileReader.open(FileContainerChannel.openForRead(file), masterKey).use { }
                    }.onFailure { sampleFailures++ }
                }
            }
            return RestoreResult.Success(
                entries = entries,
                usedBackupMeta = usedBackupMeta,
                sampled = sampled,
                sampleFailures = sampleFailures,
            )
        } catch (e: IOException) {
            return RestoreResult.Failed("归档读取失败（${e.javaClass.simpleName}）")
        } finally {
            // 没就位就把临时目录清掉：失败绝不能留下半个仓库
            if (!moved) temp.deleteRecursively()
        }
    }

    private fun crc32Of(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }
}
