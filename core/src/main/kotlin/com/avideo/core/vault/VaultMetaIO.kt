package com.avideo.core.vault

import com.avideo.core.errors.VaultCorruptedError
import com.avideo.core.errors.VaultMetaFormatError
import java.io.File

/**
 * vault.meta 与 vault.meta.bak 的读写与回退。
 *
 *   ① 读 vault.meta   → 校验 magic / 长度 / version / crc32
 *        │ 通过 → 用它
 *        └ 失败 → ②
 *   ② 读 vault.meta.bak → 同样校验
 *        │ 通过 → 用它
 *        └ 失败 → ③
 *   ③ 报「仓库元数据损坏」
 *
 * 全程**不跑 KDF**：校验必须在派生主密钥之前完成，损坏时不得用被改坏的参数去派生。
 */
object VaultMetaIO {

    const val FILE_NAME = "vault.meta"
    const val BACKUP_NAME = "vault.meta.bak"

    sealed class LoadResult {
        /** 只有一份可用（或两份一致）。 */
        data class Ok(val meta: VaultMeta, val fromBackup: Boolean) : LoadResult()

        /** 两份都通过校验但内容不同（例如用户手工替换过其中一份）。调用方 MAY 两份都试。 */
        data class BothValid(val primary: VaultMeta, val backup: VaultMeta) : LoadResult()

        /** 两份都不可用。 */
        object Corrupted : LoadResult()
    }

    /** 写主文件后**立即**用同一份字节重写 .bak（spec: vault-meta「镜像副本」）。 */
    fun write(vaultDir: File, meta: VaultMeta) {
        val bytes = meta.encode()
        File(vaultDir, FILE_NAME).writeBytes(bytes)
        File(vaultDir, BACKUP_NAME).writeBytes(bytes)
    }

    fun load(vaultDir: File): LoadResult {
        val primary = readCandidate(File(vaultDir, FILE_NAME))
        val backup = readCandidate(File(vaultDir, BACKUP_NAME))
        return when {
            primary == null && backup == null -> LoadResult.Corrupted
            primary == null -> LoadResult.Ok(backup!!, fromBackup = true)
            backup == null -> LoadResult.Ok(primary, fromBackup = false)
            primary.encode().contentEquals(backup.encode()) -> LoadResult.Ok(primary, fromBackup = false)
            else -> LoadResult.BothValid(primary, backup)
        }
    }

    /**
     * @throws VaultCorruptedError 两份都不可用时（与 [com.avideo.core.errors.WrongPasswordError] 不同类型）
     */
    fun loadOrThrow(vaultDir: File): VaultMeta = when (val result = load(vaultDir)) {
        is LoadResult.Ok -> result.meta
        is LoadResult.BothValid -> result.primary
        LoadResult.Corrupted -> throw VaultCorruptedError()
    }

    /**
     * 逐份报告两份边车是否**完整可解析**：`(primary 完整, backup 完整)`。
     *
     * 只做与 [load] 同一套的**无密钥**校验（magic / 长度 / version / crc32 / params），
     * 不跑 KDF，也不解密任何东西。文件名缺失 / 损坏一律记 `false`。
     * 供诊断与恢复流程使用：`true == true` 表示两份都在且都通得过校验。
     */
    fun integrityOf(vaultDir: File): Pair<Boolean, Boolean> =
        (readCandidate(File(vaultDir, FILE_NAME)) != null) to
            (readCandidate(File(vaultDir, BACKUP_NAME)) != null)

    private fun readCandidate(file: File): VaultMeta? {
        if (!file.isFile) return null
        return try {
            VaultMeta.decode(file.readBytes())
        } catch (e: VaultMetaFormatError) {
            null
        }
    }
}
