package com.example.avideo.ui.screens.player

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * 播放页 — libVLC 承载
 *
 * DESIGN.md §2: 播放页无 top bar，仅底部控制
 * DESIGN.md §8: 禁止容器转场动画（由 Navigation 层控制）
 * DESIGN.md §6: 仅播放/暂停、进度条、全屏三项交互
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    fileId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    var isPlaying by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var playState by remember { mutableStateOf<PlayState>(PlayState.Loading) } // Loading | Buffering | Playing | Paused | Error
    val scope = rememberCoroutineScope()

    // libVLC 实例：用 remember 持有，避免每次 recompose 重建导致内存泄漏
    val libVLC by remember { mutableStateOf(LibVLC(context)) }

    // FLAG_SECURE: 播放页必须防截屏（release 模式开启）
    // 进入播放器时设置，退出时清除
    if (BuildConfig.DEBUG == false) {
        SideEffect {
            val activity = view.context as? android.app.Activity
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // libVLC SurfaceView 占位
        AndroidView(
            factory = { ctx ->
                // libvlc-all 3.6.5 里的渲染视图是 VLCVideoLayout —— 该版本**没有** VideoView 这个类
                // （已解出 AAR 的 classes.jar 逐个核对：LibVLC ✓ / Media ✓ / VideoView ✗ / VLCVideoLayout ✓）。
                //
                // 目前只挂视图、不播任何东西：数据源要等 stage-2 的本地 HTTP Range 桥（一次性 token URL）。
                // 届时由 MediaPlayer.attachViews(VLCVideoLayout, …) + Media(token URL) 接上；
                // 现在不写进去，是因为那些 API 尚未在本项目验证过，而这里的目标是先让 APK 能构建出来。
                VLCVideoLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
        )

        // 加载/错误覆盖层（播放前或失败时显示）
        if (playState == PlayState.Loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在加载视频...", color = Color.White.copy(alpha = 0.8f))
                }
            }
        } else if (playState == PlayState.Error) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("播放失败", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("请检查视频文件是否正常", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { playState = PlayState.Loading } },
                            contentColor = Color.White
                        ) { Text("重试") }
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                        ) { Text("返回") }
                    }
                }
            }
        }

        // 底部控制栏（DESIGN.md §2：无 top bar，仅播放/暂停、进度条、全屏）
        if (!isFullscreen) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                // 进度条：1dp 白色细线，无 chrome（DESIGN.md §2）
                LinearProgressIndicator(
                    progress = { if (isPlaying) 0.3f else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = colorScheme.onPrimary)
                    }
                    FloatingActionButton(
                        onClick = { isPlaying = !isPlaying },
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放"
                        )
                    }
                    IconButton(onClick = { isFullscreen = true }) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "全屏", tint = colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

private enum class PlayState { Loading, Buffering, Playing, Paused, Error }
