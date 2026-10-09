package com.avideo.core.container

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 容器读写的**唯一** I/O 出口（design.md D1：core 只接受 File / FileChannel，由调用方传入）。
 *
 * 生产实现是 [FileContainerChannel]；测试可以替换成记录写入顺序、或中途抛错的实现——
 * 「写到 50% 被杀」「commit 写完正文未写 tag 被杀」「列出条目不解任何数据块」
 * 这三条只能靠中断/观测才能验证的性质全靠它。
 *
 * 所有偏移都是**绝对**偏移：实现 MUST NOT 依赖通道自身的位置指针。
 */
interface ContainerChannel : Closeable {

    /** 在 [offset] 处顺序写入 [src] 的前 [length] 个字节。允许越界写（文件会被拉长）。 */
    fun writeAt(offset: Long, src: ByteArray, length: Int)

    /** 读 [length] 个字节到 [dst]；返回实读字节数。读到 EOF 时返回 0（或少于 [length]）。 */
    fun readAt(offset: Long, dst: ByteArray, length: Int): Int

    fun size(): Long

    fun flush()
}

class FileContainerChannel private constructor(private val channel: FileChannel) : ContainerChannel {

    override fun writeAt(offset: Long, src: ByteArray, length: Int) {
        require(length in 0..src.size) { "length $length outside 0..${src.size}" }
        val buffer = ByteBuffer.wrap(src, 0, length)
        var position = offset
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position)
        }
    }

    override fun readAt(offset: Long, dst: ByteArray, length: Int): Int {
        require(length in 0..dst.size) { "length $length outside 0..${dst.size}" }
        val buffer = ByteBuffer.wrap(dst, 0, length)
        var total = 0
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, offset + total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    override fun size(): Long = channel.size()

    override fun flush() {
        channel.force(false)
    }

    override fun close() {
        channel.close()
    }

    companion object {
        fun openForWrite(file: File): FileContainerChannel = FileContainerChannel(
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        )

        fun openForRead(file: File): FileContainerChannel =
            FileContainerChannel(FileChannel.open(file.toPath(), StandardOpenOption.READ))
    }
}
