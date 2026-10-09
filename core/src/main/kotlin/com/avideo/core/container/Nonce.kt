package com.avideo.core.container

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 *   nonce = base_nonce(4) || counter(8)   → 12 字节（GCM 标准长度）
 *
 *   counter(0)     → header_tag
 *   counter(1)     → metadata 区
 *   counter(i + 2) → 第 i 个数据块
 *
 * 数据块从 counter(2) 开始、**不是** counter(1)：metadata 已经占了 counter(1)，
 * 照旧公式写会让 chunk_0 与 metadata 撞同一个 nonce —— 同 key 下 GCM nonce 重用是致命失效。
 */
object Nonce {
    const val LEN = 12

    fun of(baseNonce: ByteArray, counter: Long): ByteArray {
        require(baseNonce.size == ContainerConstants.BASE_NONCE_LEN) {
            "base nonce must be ${ContainerConstants.BASE_NONCE_LEN} bytes"
        }
        val nonce = ByteArray(LEN)
        baseNonce.copyInto(nonce, 0, 0, ContainerConstants.BASE_NONCE_LEN)
        ByteBuffer.wrap(nonce, ContainerConstants.BASE_NONCE_LEN, 8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(counter)
        return nonce
    }

    fun header(baseNonce: ByteArray): ByteArray = of(baseNonce, ContainerConstants.COUNTER_HEADER)

    fun metadata(baseNonce: ByteArray): ByteArray = of(baseNonce, ContainerConstants.COUNTER_METADATA)

    fun chunk(baseNonce: ByteArray, chunkIndex: Long): ByteArray =
        of(baseNonce, chunkIndex + ContainerConstants.COUNTER_FIRST_DATA_CHUNK)
}
