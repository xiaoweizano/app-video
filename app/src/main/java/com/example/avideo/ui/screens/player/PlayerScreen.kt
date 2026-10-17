package com.example.avideo.ui.screens.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.avideo.BuildConfig
import com.example.avideo.playback.PlaybackBridge
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * 播放页 —— 真数据源：本地回环 HTTP Range 桥（[com.example.avideo.service.LocalHttpService]）。
 *
 * 为什么不是「先解密到临时文件再播」：那会让磁盘上留下明文视频，直接推翻本项目的核心承诺
 * （spec: playback-session「播放加密文件不需要落盘明文」）。VLC 只看到一个
 * `http://127.0.0.1:<随机端口>/v/<一次性token>/<uuid>.mp4`，每次请求由桥按区间解密。
 *
 * 拖动进度条只解密所需块（`EncryptedFileReader.read(offset, len)`）—— 所以定位耗时与文件
 * 总大小无关。这也是方案 B 相对「整份解密」的核心价值。
 *
 * DESIGN.md §2: 播放页无 top bar，仅底部控制
 * DESIGN.md §6: 仅播放/暂停、进度条、全屏三项交互
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    fileId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = MaterialTheme.colorScheme

    var isPlaying by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var playState by remember { mutableStateOf<PlayState>(PlayState.Loading) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var seekFraction by remember { mutableStateOf<Float?>(null) }   // 拖动时暂时接管进度条
    var retryTick by remember { mutableStateOf(0) }
    var attached by remember { mutableStateOf(false) }
    var mediaUrl by remember { mutableStateOf<String?>(null) }
    var ended by remember { mutableStateOf(false) }

    val bridgeState by PlaybackBridge.state.collectAsState()
    val bridgeLog by PlaybackBridge.log.collectAsState()
    val ready = bridgeState as? PlaybackBridge.State.Ready

    // libVLC 实例与播放器：remember 持有，离开时释放（否则原生资源泄漏）。
    val libVLC = remember { LibVLC(context) }
    val mediaPlayer = remember { MediaPlayer(libVLC) }
    val layoutHolder = remember { mutableStateOf<VLCVideoLayout?>(null) }

    // 引用计数：进入 +1、离开 -1。归零 60 秒后服务自己关（不在这里持有定时器）。
    DisposableEffect(fileId) {
        PlaybackBridge.acquire(context)
        onDispose { PlaybackBridge.release(context) }
    }

    DisposableEffect(mediaPlayer) {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> playState = PlayState.Loading
                MediaPlayer.Event.Buffering -> if (playState != PlayState.Playing) playState = PlayState.Buffering
                MediaPlayer.Event.Playing -> {
                    playState = PlayState.Playing
                    isPlaying = true
                    durationMs = mediaPlayer.length
                }
                MediaPlayer.Event.Paused -> {
                    playState = PlayState.Paused
                    isPlaying = false
                }
                MediaPlayer.Event.EndReached -> {
                    playState = PlayState.Paused
                    isPlaying = false
                    ended = true
                    // 结束时把位置对齐到时长：否则 VLC 有时停在 length 之前几百毫秒，
                    // 进度条看起来「差一截」。
                    durationMs = mediaPlayer.length.coerceAtLeast(durationMs)
                    positionMs = durationMs
                }
                MediaPlayer.Event.EncounteredError -> {
                    errorText = "解码或数据源出错"
                    playState = PlayState.Error
                }
            }
        }
        onDispose {
            mediaPlayer.setEventListener(null)
            runCatching { mediaPlayer.stop() }
            runCatching { mediaPlayer.detachViews() }
            runCatching { mediaPlayer.release() }
            runCatching { libVLC.release() }
        }
    }

    // 桥就绪 + 视图就绪之后才接上媒体。两步都完成之前保持「正在加载」。
    LaunchedEffect(ready, layoutHolder.value, retryTick) {
        val layout = layoutHolder.value ?: return@LaunchedEffect
        if (ready == null) return@LaunchedEffect
        val url = PlaybackBridge.urlFor(fileId) ?: return@LaunchedEffect
        // attachViews 只能做一次：重试时只换 media，不重复附加视图
        if (!attached) {
            mediaPlayer.attachViews(layout, null, false, false)
            attached = true
        }
        val media = Media(libVLC, Uri.parse(url)).apply {
            // 硬解在密文播放里没有问题：VLC 看到的已经是桥解密后的明文流。
            setHWDecoderEnabled(true, false)
        }
        mediaPlayer.media = media
        media.release()
        mediaUrl = url
        ended = false
        mediaPlayer.play()
        playState = PlayState.Buffering
    }

    // 进度轮询：VLC 没有「位置变化」事件，只能按固定间隔取。
    LaunchedEffect(playState) {
        while (true) {
            if (playState == PlayState.Playing || playState == PlayState.Buffering) {
                positionMs = mediaPlayer.time.coerceAtLeast(0L)
                val len = mediaPlayer.length
                if (len > 0) durationMs = len
            }
            delay(500L)
        }
    }

    // 全屏 = 横屏。不能依赖系统的自动旋转：很多人的自动旋转是关着的，
    // 那样「全屏」就只剩隐藏控制栏，等于没做。退出时恢复 unspecified。
    val activity = context as? android.app.Activity
    val window = activity?.window
    LaunchedEffect(isFullscreen) {
        activity?.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // 「全屏」必须是真全屏：只藏控制栏而状态栏还在，观感上等于没全屏。
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    if (BuildConfig.DEBUG == false) {
        SideEffect {
            val activity = view.context as? android.app.Activity
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                // libvlc-all 3.6.5 的渲染视图是 VLCVideoLayout（该版本没有 VideoView）。
                VLCVideoLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    layoutHolder.value = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 全屏时点任意处退出。这一层刻意**放在控件之前**（因此位于控件下方）：
        // 之前把它做成外层 Box 上的 clickable，结果它在进度条的祖先链上，
        // 怀疑是它吃了进度条的拖拽 —— 无论如何，控件不该待在一个 clickable 里。
        if (isFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { isFullscreen = false },
            )
        }

        when (playState) {
            PlayState.Loading, PlayState.Buffering -> Overlay {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (ready == null) "正在启动本机播放服务…" else "正在解密与缓冲…",
                    color = Color.White.copy(alpha = 0.8f),
                )
            }

            PlayState.Error -> Overlay {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("播放失败", color = Color.White, style = MaterialTheme.typography.titleSmall)
                errorText?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            errorText = null
                            playState = PlayState.Loading
                            retryTick++          // 重新建 media 并 play（视图不重复附加）
                        },
                        // material3 的 OutlinedButton 没有 contentColor 参数（那是 material2 的写法）
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text("重试") }
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    ) { Text("退出") }
                }
            }

            else -> Unit
        }

        if (!isFullscreen) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                // 自绘进度条，不用 Material3 Slider。
                // 原因：真机实测「白色圆点完全不动」—— 触摸事件根本没到控件上，而同一列里
                // 的按钮却正常。排查顺序是先把祖先链上的 clickable 挪走（见上），
                // 再把这个唯一失效的控件换掉：它的触控只有这一个实现，出问题就整体重写，
                // 比在一堆图层里猜谁吃了事件更快。
                SeekBar(
                    fraction = seekFraction
                        ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onScrub = { seekFraction = it },
                    onSeek = { fraction ->
                        // 只定位，不解码整份 —— 桥那边只解你拖到的这一块
                        if (durationMs > 0) mediaPlayer.time = (fraction * durationMs).toLong()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
                Text(
                    "${formatTime(seekFraction?.let { (it * durationMs).toLong() } ?: positionMs)} / " +
                        formatTime(durationMs),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
                if (BuildConfig.DEBUG) {
                    // 诊断用：判「时长差几秒」到底是 VLC 报得短，还是数据被截断。
                    Text(
                        "[诊断] VLC len=${durationMs}ms pos=${positionMs}ms " +
                            "seekable=${mediaPlayer.isSeekable} ended=$ended",
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                    )
                    // 桥的请求日志：拖动卡死时，看最后几条就知道 VLC 到底发了什么、
                    // 我们回了什么、有没有僵尸连接被看门狗收走。
                    bridgeLog.takeLast(4).forEach { line ->
                        Text(
                            "  $line",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 9.sp,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = colorScheme.onPrimary)
                    }
                    FloatingActionButton(
                        onClick = {
                            when {
                                isPlaying -> mediaPlayer.pause()
                                // 从 Ended 状态直接 play() 不会重头放：VLC 的输入已经结束，
                                // 必须换一个新 media（同一个 URL）再播放。
                                ended -> {
                                    ended = false
                                    playState = PlayState.Buffering
                                    positionMs = 0L
                                    retryTick++
                                }
                                else -> mediaPlayer.play()
                            }
                        },
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                        )
                    }
                    IconButton(
                        onClick = { isFullscreen = true },
                        // 半透明圆底：白图标压在画面上时，遇到亮背景就看不见了
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape),
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "全屏", tint = Color.White)
                    }
                }
            }
        } else {
            // 全屏时只留一个退出按钮：点画面任意处也会退出（见外层 clickable）
            IconButton(
                onClick = { isFullscreen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
            }
        }
    }
}

/**
 * 自绘进度条：3 dp 的视觉线 + 40 dp 的触控高度。
 *
 * 不用 Material3 Slider 的原因写在调用处：真机上它完全收不到拖拽事件，
 * 而同一列里的按钮正常。自己实现 pointerInput 之后，触摸链就是
 * 「一个明确高度的 Canvas + detectHorizontalDragGestures」，没有别的图层能挡住它。
 */
@Composable
private fun SeekBar(
    fraction: Float,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val shown = (if (dragging) dragFraction else fraction).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        // 按下就跳到按下的位置：不必先滑过一段阈值
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrub(dragFraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(dragFraction)
                        change.consume()
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek(dragFraction)
                        onScrub(null)
                    },
                    onDragCancel = {
                        dragging = false
                        onScrub(null)
                    },
                )
            },
    ) {
        val centerY = size.height / 2f
        val thumbX = (size.width * shown).coerceIn(0f, size.width)
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 3f,
        )
        drawLine(
            color = Color.White,
            start = Offset(0f, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = 3f,
        )
        drawCircle(color = Color.White, radius = 8.dp.toPx(), center = Offset(thumbX, centerY))
    }
}

@Composable
private fun Overlay(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private enum class PlayState { Loading, Buffering, Playing, Paused, Error }
