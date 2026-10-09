package com.avideo.core.vault

import com.avideo.core.container.ContainerChannel
import com.avideo.core.container.ContainerClassifier
import com.avideo.core.container.ContainerConstants
import com.avideo.core.container.EncryptedFileReader
import com.avideo.core.container.EncryptedFileWriter
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.KdfParams
import com.avideo.core.crypto.RandomSource
import com.avideo.core.errors.UncommittedFileError
import com.avideo.core.errors.VaultError
import java.io.File

/**
 * 仓库目录：
 *
 *   <vault>/
 *     vault.meta        明文边车
 *     vault.meta.bak    镜像副本
 *     data/<uuid>.bin   每个视频一个密文文件
 *
 * 没有任何索引文件——列出条目 = 枚举 data/ 目录 + 逐个解 metadata 区。
 *
 * [channelFactory] 只为可测性存在：stage-2 的「列出条目不解任何数据块」
 * 需要观察到读取范围，生产路径用默认的 [FileContainerChannel.openForRead]。
 */
class VaultDirectory(
    val root: File,
    private val channelFactory: (File) -> ContainerChannel = { FileContainerChannel.openForRead(it) },
) {

    val metaFile: File get() = File(root, VaultMetaIO.FILE_NAME)
    val backupFile: File get() = File(root, VaultMetaIO.BACKUP_NAME)
    val dataDir: File get() = File(root, DATA_DIR)

    /** 创建仓库：生成随机盐，写出 vault.meta 与 .bak。 */
    fun create(params: KdfParams, random: RandomSource = RandomSource.secure()): VaultMeta {
        root.mkdirs()
        dataDir.mkdirs()
        val meta = VaultMeta.create(random, params)
        VaultMetaIO.write(root, meta)
        return meta
    }

    fun loadMeta(): VaultMeta = VaultMetaIO.loadOrThrow(root)

    fun cipherFile(uuid: String): File = File(dataDir, "$uuid.bin")

    /** data/ 下所有密文文件（按名字排序，保证列表稳定）。 */
    fun cipherFiles(): List<File> =
        dataDir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun newWriter(uuid: String, masterKey: ByteArray): EncryptedFileWriter =
        EncryptedFileWriter(FileContainerChannel.openForWrite(cipherFile(uuid)), masterKey)

    /**
     * 已提交的条目。未提交的（导入被杀留下的）直接排除；单个损坏的条目降级为
     * [VaultEntry.Corrupted] 而不是让整个列表失败。
     */
    fun list(masterKey: ByteArray): List<VaultEntry> = cipherFiles().mapNotNull { file ->
        val uuid = file.name.removeSuffix(SUFFIX)
        val channel = channelFactory(file)
        var reader: EncryptedFileReader? = null
        try {
            reader = EncryptedFileReader.open(channel, masterKey)
            VaultEntry.Committed(uuid, file, reader.metadata)
        } catch (e: UncommittedFileError) {
            null                                     // 未提交：列表 MUST 排除
        } catch (e: VaultError) {
            // 只带错误类型名，不带任何路径/文件名之外的敏感信息
            VaultEntry.Corrupted(uuid, file, e.javaClass.simpleName)
        } finally {
            if (reader != null) reader.close() else channel.close()
        }
    }

    /** 不需要密码：靠全零的 metadata_tag 判定（计划 D-1）。孤儿清理的入口。 */
    fun listUncommitted(): List<File> = cipherFiles().filter { file ->
        channelFactory(file).use { ContainerClassifier.classify(it) == ContainerClassifier.State.UNCOMMITTED }
    }

    companion object {
        const val DATA_DIR = "data"
        const val SUFFIX = ".bin"
        const val DEFAULT_METADATA_LEN = ContainerConstants.METADATA_LEN
    }
}

/** 列表条目。 */
sealed class VaultEntry {
    abstract val uuid: String
    abstract val file: File

    data class Committed(
        override val uuid: String,
        override val file: File,
        val metadata: com.avideo.core.container.FileMetadata,
    ) : VaultEntry()

    /** [reason] 只含错误类型名，不含敏感值。 */
    data class Corrupted(
        override val uuid: String,
        override val file: File,
        val reason: String,
    ) : VaultEntry()
}
