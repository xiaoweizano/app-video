package com.avideo.core

import com.avideo.core.container.ContainerChannel

/**
 * 记录每一次写入的 (偏移, 长度)，并且可以在第 N 次写入时抛异常。
 * 「commit 的写入顺序」和「写完正文但未写 tag 时被杀」两条只能靠中断才能验证的性质，
 * 全靠这个替身变成普通单元测试（计划 D-3）。
 *
 * [events] 额外按**发生顺序**记录写入与 flush：「正文写完之后、tag 写下去之前 MUST 有一次
 * 落盘屏障」这条只能靠观测两者的交错顺序来验证，光看 [writes] 看不见 flush。
 */
class RecordingChannel(
    private val delegate: ContainerChannel,
    private val failAtWriteIndex: Int? = null,
) : ContainerChannel {

    data class WriteCall(val offset: Long, val length: Int)

    /** 写入与 flush 的发生顺序。 */
    sealed class Event {
        data class Write(val call: WriteCall) : Event()
        object Flush : Event()
    }

    val writes = mutableListOf<WriteCall>()
    val events = mutableListOf<Event>()

    override fun writeAt(offset: Long, src: ByteArray, length: Int) {
        if (failAtWriteIndex != null && writes.size == failAtWriteIndex) {
            throw SimulatedCrashError("simulated crash before write #${writes.size}")
        }
        writes += WriteCall(offset, length)
        events += Event.Write(WriteCall(offset, length))
        delegate.writeAt(offset, src, length)
    }

    override fun readAt(offset: Long, dst: ByteArray, length: Int): Int = delegate.readAt(offset, dst, length)
    override fun size(): Long = delegate.size()
    override fun flush() {
        events += Event.Flush
        delegate.flush()
    }
    override fun close() = delegate.close()
}

class SimulatedCrashError(message: String) : Error(message)
