package com.example.avideo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.avideo.core.container.EncryptedFileReader
import com.avideo.core.container.FileContainerChannel
import com.avideo.core.crypto.PasswordNormalizer
import com.avideo.core.errors.VaultError
import com.avideo.core.playback.RangeSpec
import com.avideo.core.playback.parseRange
import com.example.avideo.crypto.KeyRepository
import com.example.avideo.crypto.VaultGateway
import com.example.avideo.playback.PlaybackBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 本地 HTTP Range 桥：把密文按明文区间解密后喂给 libVLC，磁盘上永不出现明文。
 *
 * 安全边界只有三样，缺一不可：
 *  1. **只绑 127.0.0.1**。`getLoopbackAddress()` 在部分设备上返回 `::1`（IPv6），
 *     所以这里显式取 IPv4 字面量 —— spec 要求就是 `MUST 为 127.0.0.1`。
 *  2. **一次性随机 token**。127.0.0.1 是同一台设备上所有 App 共享的，端口随机不是安全边界，
 *     token 才是。比较用 [MessageDigest.isEqual]（常数时间，避免时序侧信道）。
 *  3. **不缓存主密钥**：每次请求从 [KeyRepository] 取一份副本，用完立刻清零；
 *     reader 每次请求现开现关（关闭即清零它自己的 data key）。
 *
 * 并发：libVLC 会同时发多个 Range 请求（探测容器头 + 预读），串行处理会让它判定超时。
 * 用固定大小线程池 —— 并发有上限，不无限建线程（spec: playback-bridge「服务必须并发处理」）。
 */
class LocalHttpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = Executors.newFixedThreadPool(MAX_CONCURRENCY)
    private var serverSocket: ServerSocket? = null

    private var token: String = ""
    private var tokenCreatedAt: Long = 0L

    /**
     * 活动连接 → 最后一次有进度的时刻。
     *
     * 为什么需要：VLC 在向后拖动时会**弃用**当前连接并另开一条，而被弃的那条可能
     * 还卡在阻塞写里（对端不再读，TCP 发送缓冲塞满，`OutputStream.write` 一直不返回，
     * 而且 Java 的 socket 超时只对读生效，对写无效）。弃用连接一多，线程池被占满，
     * 后续请求全部排队 —— 表现就是「向后拖之后画面卡死」。
     * 看门狗按这个表把长时间没有进度的连接强行关掉，阻塞写会立刻抛 IOException、线程归还。
     */
    private val activeSockets = ConcurrentHashMap<Socket, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (serverSocket == null) startServer()
        // 不重启：进程没了播放也没了，重启服务只会拿到新端口/新 token，
        // 播放器手里那个旧 URL 会永远失败。播放页会重新 acquire。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        scope.cancel()
        PlaybackBridge.markStopped()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 服务端

    private fun startServer() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val socket = ServerSocket(0, BACKLOG, loopback)
        serverSocket = socket
        token = randomToken()
        tokenCreatedAt = System.currentTimeMillis()
        PlaybackBridge.publish(socket.localPort, token)

        scope.launch {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break                       // socket 被关掉（onDestroy）或监听失败
                }
                pool.execute { handleSafely(client) }
            }
        }
        scope.launch { watchdog() }
    }

    /** 关掉「长时间没有写进度」的连接，把被阻塞写占住的线程还回池子。 */
    private suspend fun watchdog() {
        while (true) {
            delay(WATCHDOG_INTERVAL_MS)
            val now = System.currentTimeMillis()
            activeSockets.entries.toList().forEach { (socket, lastActive) ->
                if (now - lastActive > STALL_TIMEOUT_MS) {
                    PlaybackBridge.note("watchdog: 关闭停滞连接（${now - lastActive}ms 无进展）")
                    runCatching { socket.close() }
                    activeSockets.remove(socket)
                }
            }
        }
    }

    private fun handleSafely(client: Socket) {
        activeSockets[client] = System.currentTimeMillis()
        try {
            client.use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                handle(socket)
            }
        } catch (e: IOException) {
            PlaybackBridge.note("连接中断：${e.javaClass.simpleName}")
        } finally {
            activeSockets.remove(client)
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHeaderBlock(input) ?: return
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val requestLine = lines.first().split(" ")
        if (requestLine.size < 2) return
        val method = requestLine[0].uppercase()
        val path = requestLine[1].substringBefore('?')

        val headers = lines.drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null
            else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()

        val out = BufferedOutputStream(TrackedOutputStream(socket, socket.getOutputStream()))

        fun reply(
            code: Int,
            reason: String,
            extra: List<Pair<String, String>> = emptyList(),
            writeBody: (OutputStream) -> Unit = {},
        ) {
            out.write("HTTP/1.1 $code $reason\r\n".toByteArray(Charsets.US_ASCII))
            extra.forEach { (name, value) ->
                out.write("$name: $value\r\n".toByteArray(Charsets.US_ASCII))
            }
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            writeBody(out)
            out.flush()
        }

        val rangeHeader = headers["range"]
        PlaybackBridge.note("$method Range=${rangeHeader ?: "-"}")

        if (method != "GET" && method != "HEAD") {
            reply(405, "Method Not Allowed")
            return
        }

        // 路径：/v/<token>/<uuid>.mp4
        val segments = path.trim('/').split('/')
        if (segments.size < 3 || segments[0] != "v") {
            reply(404, "Not Found")
            return
        }
        if (expired() || !tokenMatches(segments[1])) {
            // 不带任何明文字节，也不回显 token
            reply(401, "Unauthorized")
            return
        }
        val uuid = segments[2].removeSuffix(".mp4")

        // 未解锁 ⇒ 明确 503，而不是 404：播放页可以据此提示「重新解锁」
        val masterKey = KeyRepository.getMasterKey()
        if (masterKey == null) {
            reply(503, "Service Unavailable")
            return
        }
        try {
            val directory = VaultGateway.directory(this)
            val file = try {
                directory.cipherFile(uuid)
            } catch (e: IllegalArgumentException) {
                reply(404, "Not Found")
                return
            }
            if (!file.isFile) {
                reply(404, "Not Found")
                return
            }
            FileContainerChannel.openForRead(file).use { channel ->
                val reader = try {
                    EncryptedFileReader.open(channel, masterKey)
                } catch (e: VaultError) {
                    reply(404, "Not Found")     // 未提交 / 损坏 / 密钥不对，一律不区分
                    return
                } catch (e: IOException) {
                    reply(404, "Not Found")
                    return
                }
                reader.use { serve(it, method, headers, ::reply) }
            }
        } finally {
            PasswordNormalizer.wipe(masterKey)
        }
    }

    private fun serve(
        reader: EncryptedFileReader,
        method: String,
        headers: Map<String, String>,
        reply: (Int, String, List<Pair<String, String>>, (OutputStream) -> Unit) -> Unit,
    ) {
        val length = reader.plaintextLength
        // Content-Type 用 octet-stream：我们不知道容器格式，报一个具体的类型只会误导解复用器。
        // 播放器靠文件内容嗅探，不靠这个头。
        val base = listOf(
            "Accept-Ranges" to "bytes",
            "Content-Type" to "application/octet-stream",
            // 解密后的字节不该被任何缓存留存（回环也一样，这是个态度问题）
            "Cache-Control" to "no-store",
        )

        when (val range = parseRange(headers["range"], length)) {
            RangeSpec.Unsatisfiable -> reply(
                416,
                "Range Not Satisfiable",
                base + ("Content-Range" to "bytes */$length"),
            ) { /* MUST NOT 返回任何明文字节 */ }.also {
                PlaybackBridge.note("  → 416 越界（明文长度 $length）")
            }

            RangeSpec.Whole -> reply(
                200,
                "OK",
                base + ("Content-Length" to "$length"),
            ) { body ->
                if (method != "HEAD") stream(reader, 0L, length, body)
            }

            is RangeSpec.Bytes -> {
                val count = range.end - range.start + 1L
                reply(
                    206,
                    "Partial Content",
                    base + listOf(
                        "Content-Length" to "$count",
                        // Content-Range 用**明文偏移**标注，不是磁盘偏移（spec: playback-bridge）
                        "Content-Range" to "bytes ${range.start}-${range.end}/$length",
                    ),
                ) { body ->
                    if (method != "HEAD") stream(reader, range.start, count, body)
                }
                PlaybackBridge.note("  → 206 ${range.start}-${range.end}/$length（$count B）")
            }
        }
    }

    /** 每次写出都刷新「最后一次有进度的时刻」，供看门狗判断连接是否停滞。 */
    private inner class TrackedOutputStream(
        private val socket: Socket,
        private val delegate: OutputStream,
    ) : OutputStream() {
        override fun write(b: Int) {
            activeSockets[socket] = System.currentTimeMillis()
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            activeSockets[socket] = System.currentTimeMillis()
            delegate.write(b, off, len)
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    /** 按区间解密并写出去。块边界由 reader 自己处理，这里只管切 ≤64 KiB 的片。 */
    private fun stream(reader: EncryptedFileReader, start: Long, count: Long, out: OutputStream) {
        var offset = start
        var remaining = count
        while (remaining > 0L) {
            val n = minOf(remaining, IO_CHUNK.toLong()).toInt()
            out.write(reader.read(offset, n))
            offset += n.toLong()
            remaining -= n.toLong()
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 读请求头块（到空行为止），8 KiB 封顶 —— 别让一个请求把内存吃穿。 */
    private fun readHeaderBlock(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (buffer.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            buffer.write(b)
            matched = when {
                b.toByte() == terminator[matched] -> matched + 1
                b.toByte() == terminator[0] -> 1
                else -> 0
            }
            if (matched == 4) return buffer.toString("ISO-8859-1")
        }
        return null
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun tokenMatches(candidate: String): Boolean =
        MessageDigest.isEqual(candidate.toByteArray(Charsets.US_ASCII), token.toByteArray(Charsets.US_ASCII))

    private fun expired(): Boolean = System.currentTimeMillis() - tokenCreatedAt > TOKEN_TTL_MS

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "播放加密视频时的前台服务通知"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在播放")
            .setContentText("通过本机回环地址解密播放，磁盘上不会出现明文")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 0x1A2C

        /**
         * 并发上限。VLC 会为「探测 + 预读 + 拖动后的重连」同时开好几条连接，
         * 而向后拖动时被弃用的连接要等看门狗收尸；留足余量，别让池子被几条僵尸连接占满。
         */
        const val MAX_CONCURRENCY = 12

        /** 看门狗：多久检查一次、多少毫秒没有写进度就认定这条连接已停滞。 */
        const val WATCHDOG_INTERVAL_MS = 3_000L
        const val STALL_TIMEOUT_MS = 20_000L
        const val BACKLOG = 16
        const val SOCKET_TIMEOUT_MS = 15_000
        const val MAX_HEADER_BYTES = 8 * 1024
        const val IO_CHUNK = 64 * 1024
        const val TOKEN_BYTES = 32
        const val TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
