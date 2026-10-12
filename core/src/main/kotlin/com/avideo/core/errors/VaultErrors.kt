package com.avideo.core.errors

/**
 * 容器层与仓库层的全部预期错误。
 *
 * 硬约束（spec: vault-crypto「日志与异常不泄漏敏感信息」）：
 * 所有 message **只许**含结构性信息（偏移、长度、字段名、UUID）。
 * 不得含密码、主密钥、data key、明文显示名。
 */
sealed class VaultError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** wrapped_file_key 解包失败：主密钥不对（密码错），或该 60 字节被篡改。 */
class KeyUnwrapFailedError(
    message: String = "encrypted file key could not be unwrapped",
    cause: Throwable? = null,
) : VaultError(message, cause)

/** header_tag 校验失败：固定头被篡改，或 data key 不对。 */
class HeaderAuthenticationFailedError(
    message: String = "container header authentication failed",
    cause: Throwable? = null,
) : VaultError(message, cause)

/** metadata_tag 校验失败。 */
class MetadataAuthenticationFailedError(
    message: String = "container metadata authentication failed",
    cause: Throwable? = null,
) : VaultError(message, cause)

/** 某个数据块 tag 校验失败。只带块序号，不带任何字节。 */
class ChunkAuthenticationFailedError(val chunkIndex: Long, cause: Throwable? = null) :
    VaultError("data chunk $chunkIndex failed authentication", cause)

/** 结构不合法：magic / 长度 / metadata_len / real_len / flags。 */
class MalformedContainerError(message: String) : VaultError(message)

class UnsupportedContainerVersionError(val version: Int) :
    VaultError("unsupported container version: $version")

/** 实际大小小于期望 → 截断（MUST NOT 当作完整文件返回）。 */
class TruncatedContainerError(val expected: Long, val actual: Long) :
    VaultError("container truncated: expected $expected bytes, found $actual")

/** metadata_tag 全零 / real_len == 0：占位已写但没有提交。 */
class UncommittedFileError : VaultError("container is not committed")

/** 明文区间越界。 */
class RangeOutOfBoundsError(val offset: Long, val length: Int, val plaintextLength: Long) :
    VaultError(rangeOutOfBoundsMessage(offset, length, plaintextLength))

/**
 * 拼越界消息。**MUST NOT 用 `offset + length` 拼终点**：`read(Long.MAX_VALUE, 1)` 正是这个错误
 * 存在的理由，那个加法在那里回绕成负数，消息会渲染成 `[...807, -...808)`——
 * 把「越界」说成一个诡异的负数区间，比不报还糟。
 * 终点可计算时照常报终点，不可计算（会溢出 / 长度为负）时才退化成报长度。
 */
private fun rangeOutOfBoundsMessage(offset: Long, length: Int, plaintextLength: Long): String {
    val end = if (length >= 0 && offset <= Long.MAX_VALUE - length) offset + length else null
    val shown = if (end == null) "[$offset, +$length)" else "[$offset, $end)"
    return "range $shown is outside [0, $plaintextLength)"
}

/** 源流为空：导入流程直接拒绝 0 字节文件。 */
class EmptySourceError : VaultError("source stream is empty")

/** vault.meta 自身的结构/校验和问题（会被 IO 层翻译成「回退 .bak」）。 */
class VaultMetaFormatError(message: String) : VaultError(message)

/** vault.meta 与 vault.meta.bak 两份都不可用：**仓库元数据损坏**。 */
class VaultCorruptedError(message: String = "vault metadata is corrupted") : VaultError(message)

/**
 * 密码不对。
 * MUST 与 [VaultCorruptedError] 是不同的类型、不同的提示（spec: vault-meta「读取优先级与回退」）。
 */
class WrongPasswordError(message: String = "wrong password") : VaultError(message)
