package com.avideo.core

import com.avideo.core.container.ContainerChannel

/**
 * 记录每一次写入的 (偏移, 长度)，并且可以在第 N 次写入时抛异常。
 * 「commit 的写入顺序」和「写完正文但未写 tag 时被杀」两条只能靠中断才能验证的性质，
 * 全靠这个替身变成普通单元测试（计划 D-3）。
 */
class RecordingChannel(
    private val delegate: ContainerChannel,
    private val failAtWriteIndex: Int? = null,
) : ContainerChannel {

    data class WriteCall(val offset: Long, val length: Int)

    val writes = mutableListOf<WriteCall>()

    override fun writeAt(offset: Long, src: ByteArray, length: Int) {
        if (failAtWriteIndex != null && writes.size == failAtWriteIndex) {
            throw SimulatedCrashError("simulated crash before write #${writes.size}")
        }
        writes += WriteCall(offset, length)
        delegate.writeAt(offset, src, length)
    }

    override fun readAt(offset: Long, dst: ByteArray, length: Int): Int = delegate.readAt(offset, dst, length)
    override fun size(): Long = delegate.size()
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

class SimulatedCrashError(message: String) : Error(message)
