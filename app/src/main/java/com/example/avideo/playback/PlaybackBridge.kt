package com.example.avideo.playback

import android.content.Context
import android.content.Intent
import com.example.avideo.service.LocalHttpService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放桥的「会话」侧：谁在用服务、什么时候可以关。
 *
 * 服务本身（[LocalHttpService]）只管监听与解密；这里管**生命周期**：
 *  - 播放页进入 → [acquire]（引用计数 +1，必要时拉起前台服务）
 *  - 播放页离开 → [release]（引用计数 -1；归零后 N 秒关服务，
 *    spec: playback-bridge「最后一个播放器退出后 N 秒自动关闭」「MUST NOT 无播放器时常驻」）
 *
 * 端口与 token 由服务发布在这里，播放页据此拼出 URL。
 */
object PlaybackBridge {

    sealed interface State {
        object Stopped : State
        data class Ready(val port: Int, val token: String) : State
    }

    /**
     * 「最后一个播放器退出后」再等这么久才关服务。
     * 给旋屏、短暂切换、VLC 的异步收尾留出余地 —— 关太急会让一次误触中断播放。
     */
    const val IDLE_SHUTDOWN_MS = 60_000L

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val players = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 播放页进入时调用。 */
    fun acquire(context: Context) {
        players.incrementAndGet()
        // 从可见的 Activity 里启动前台服务是允许的（后台启动 FGS 才会被拒）。
        context.applicationContext.startForegroundService(
            Intent(context, LocalHttpService::class.java)
        )
    }

    /** 播放页离开时调用。 */
    fun release(context: Context) {
        val remaining = players.decrementAndGet()
        if (remaining > 0) return
        val app = context.applicationContext
        scope.launch {
            delay(IDLE_SHUTDOWN_MS)
            if (players.get() <= 0) app.stopService(Intent(app, LocalHttpService::class.java))
        }
    }

    /** 播放 URL。服务还没起来时返回 null（播放页应先等 [state] 变成 Ready）。 */
    fun urlFor(uuid: String): String? = when (val current = _state.value) {
        is State.Ready -> "http://127.0.0.1:${current.port}/v/${current.token}/$uuid.mp4"
        State.Stopped -> null
    }

    // ---- 以下只给服务用 ----

    internal fun publish(port: Int, token: String) {
        _state.value = State.Ready(port, token)
    }

    internal fun markStopped() {
        _state.value = State.Stopped
    }

    internal fun activePlayers(): Int = players.get()
}
