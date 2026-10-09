package com.avideo.core.container

import com.avideo.core.crypto.KeyWrapping
import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.crypto.RandomSource
import com.avideo.core.errors.EmptySourceError
import java.io.Closeable
import java.io.InputStream
import java.security.MessageDigest

/**
 * 密文文件写入路径。三段式提交协议（spec: import-commit-protocol）：
 *
 *   ① [writePlaceholder]  固定头 + header_tag + 占位 metadata 区 + **全零** metadata_tag
 *   ② [writeChunk]        逐块加密写盘，同时累加 SHA-256（不额外重读源文件）
 *   ③ [commit]            必要时先按实读长度修正 plaintext_len + header_tag，
 *                         再写 metadata 正文，**最后**写 tag   ★ 提交点
 *
 * 未提交判据：metadata_tag 全为 0（无需密码，见 [ContainerClassifier]）。
 * 因此 commit 的最后一步必须是写 tag —— 顺序写反会出现「tag 正确但正文仍是占位」的窗口。
 *
 * 数据块的 AAD 为空（计划 D-4）：固定头在 commit 阶段可能被重写，
 * 若数据块把固定头当 AAD，那一次重写会让所有数据块的 tag 立刻失效。
 */
class EncryptedFileWriter(
    private val channel: ContainerChannel,
    private val masterKey: ByteArray,
    private val metadataLen: Int = ContainerConstants.METADATA_LEN,
    private val random: RandomSource = RandomSource.secure(),
) : Closeable {

    init {
        require(masterKey.size == ContainerConstants.KEYS_LEN) { "master key must be ${ContainerConstants.KEYS_LEN} bytes" }
        require(metadataLen > 0) { "metadata length must be positive" }
    }

    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private var dataKey: ByteArray = ByteArray(0)
    private var baseNonce: ByteArray = ByteArray(0)
    private var header: FixedHeader? = null
    private var writtenPlaintext: Long = 0L
    private var lastChunkWasShort: Boolean = false
    private var placeholderWritten: Boolean = false
    private var committed: Boolean = false
    private var closed: Boolean = false

    /**
     * ① 写占位。[declaredPlaintextLen] 是调用方声明的长度，允许不准（计划 D-2）：
     * commit 阶段若与实读长度不符，会重写 plaintext_len 与 header_tag。
     */
    fun writePlaceholder(declaredPlaintextLen: Long) {
        check(!placeholderWritten) { "placeholder has already been written" }
        require(declaredPlaintextLen >= 0) { "declared plaintext length must be non-negative" }

        dataKey = ByteArray(ContainerConstants.DATA_KEY_LEN).also { random.nextBytes(it) }
        baseNonce = ByteArray(ContainerConstants.BASE_NONCE_LEN).also { random.nextBytes(it) }

        val wrapped = KeyWrapping.wrap(masterKey, dataKey, random)
        val current = FixedHeader(
            version = ContainerConstants.VERSION,
            wrappedFileKey = wrapped,
            baseNonce = baseNonce,
            plaintextLen = declaredPlaintextLen,
            metadataLen = metadataLen,
        )
        val headerBytes = current.encode()
        val headerTag = Aead.tagOnly(dataKey, Nonce.header(baseNonce), headerBytes)

        channel.writeAt(0L, headerBytes + headerTag, ContainerConstants.FIXED_HEADER_LEN + ContainerConstants.HEADER_TAG_LEN)

        val placeholder = FileMetadata.encodePlaceholder(metadataLen)
        val sealed = Aead.seal(dataKey, Nonce.metadata(baseNonce), headerBytes, placeholder)
        channel.writeAt(OffsetMapping.METADATA_OFFSET, sealed, metadataLen)
        channel.writeAt(OffsetMapping.metadataTagOffset(metadataLen), ZERO_TAG, ContainerConstants.METADATA_TAG_LEN)
        channel.flush()

        header = current
        placeholderWritten = true
    }

    /** ② 加密一块明文并写盘。[length] ≤ CHUNK_PLAINTEXT；只有最后一块可以短。 */
    fun writeChunk(plaintext: ByteArray, length: Int = plaintext.size) {
        requireHeader()
        check(!committed) { "file is already committed" }
        check(!lastChunkWasShort) { "a short chunk must be the last one" }
        require(length in 1..ContainerConstants.CHUNK_PLAINTEXT) {
            "chunk length must be 1..${ContainerConstants.CHUNK_PLAINTEXT}, got $length"
        }
        require(length <= plaintext.size) { "length $length exceeds the buffer ${plaintext.size}" }

        val chunkIndex = OffsetMapping.chunkCount(writtenPlaintext)
        val sealed = Aead.seal(
            dataKey,
            Nonce.chunk(baseNonce, chunkIndex),
            EMPTY_AAD,
            plaintext.copyOf(length),
        )
        channel.writeAt(OffsetMapping.diskOffset(writtenPlaintext, metadataLen), sealed, sealed.size)

        digest.update(plaintext, 0, length)
        writtenPlaintext += length
        lastChunkWasShort = length < ContainerConstants.CHUNK_PLAINTEXT
        // 声明长度允许不准（计划 D-2），所以这里**不**断言 writtenPlaintext ≤ 声明值。
    }

    /**
     * ③ 提交。返回**实际写进去的** metadata（显示名可能是截断后的）。
     *
     * @param actualPlaintextLen 实读到的明文长度；默认取已写入的字节数。
     */
    fun commit(
        displayName: String,
        durationMs: Long,
        actualPlaintextLen: Long = writtenPlaintext,
    ): FileMetadata {
        val current = requireHeader()
        check(!committed) { "file is already committed" }
        require(actualPlaintextLen == writtenPlaintext) {
            "actualPlaintextLen ($actualPlaintextLen) must equal the bytes written ($writtenPlaintext)"
        }

        // 先修正头部（若声明长度不准）。这一步必须在写正文之前：
        // 它决定 metadata 区加密时用的 AAD，也决定 header_tag 是否与最终头部一致。
        var effective = current
        if (current.plaintextLen != actualPlaintextLen) {
            effective = current.withPlaintextLen(actualPlaintextLen)
            val headerBytes = effective.encode()
            channel.writeAt(0L, headerBytes, ContainerConstants.FIXED_HEADER_LEN)
            val headerTag = Aead.tagOnly(dataKey, Nonce.header(baseNonce), headerBytes)
            channel.writeAt(OffsetMapping.HEADER_TAG_OFFSET, headerTag, ContainerConstants.HEADER_TAG_LEN)
        }

        val metadata = FileMetadata(displayName, durationMs, digest.digest()).truncatedTo(metadataLen)
        val plaintext = FileMetadata.encode(metadata, metadataLen)
        // sealed = ciphertext(metadata_len) || tag(16)
        val sealed = Aead.seal(dataKey, Nonce.metadata(baseNonce), effective.encode(), plaintext)
        val metadataTag = sealed.copyOfRange(metadataLen, sealed.size)

        // 正文在前、tag 在后 —— 顺序是规格要求（见类注释）。tag 这一次写就是提交点。
        channel.writeAt(OffsetMapping.METADATA_OFFSET, sealed, metadataLen)
        channel.writeAt(OffsetMapping.metadataTagOffset(metadataLen), metadataTag, metadataTag.size)
        channel.flush()

        header = effective
        committed = true
        return metadata
    }

    /**
     * 便捷全流程：占位 → 流式分块 → 提交。只完整读取源流一次。
     *
     * @throws EmptySourceError 源流为空（导入流程直接拒绝 0 字节文件）。
     */
    fun writeFrom(
        source: InputStream,
        displayName: String,
        declaredPlaintextLen: Long,
        durationMs: Long = ContainerConstants.DURATION_UNKNOWN,
    ): FileMetadata {
        writePlaceholder(declaredPlaintextLen)
        val buffer = ByteArray(ContainerConstants.CHUNK_PLAINTEXT)
        while (true) {
            val n = fill(source, buffer)
            if (n == 0) break
            writeChunk(buffer, n)
            // fill 只在撞到 EOF 时才会返回少于缓冲区的大小，所以这一块必然是最后一块。
            // 立刻收敛：否则下一轮循环会为了确认 EOF 再问一次源流，
            // 违反「源流只完整读取一遍」（测试 `source stream is fully read exactly once`）。
            if (n < buffer.size) break
        }
        if (writtenPlaintext == 0L) throw EmptySourceError()
        return commit(displayName, durationMs, writtenPlaintext)
    }

    override fun close() {
        if (closed) return
        closed = true
        PasswordNormalizer.wipe(dataKey)
        channel.close()
    }

    private fun requireHeader(): FixedHeader =
        header ?: throw IllegalStateException("writePlaceholder must be called first")

    private fun fill(source: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = source.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private companion object {
        val EMPTY_AAD = ByteArray(0)
        val ZERO_TAG = ByteArray(ContainerConstants.METADATA_TAG_LEN)
    }
}
