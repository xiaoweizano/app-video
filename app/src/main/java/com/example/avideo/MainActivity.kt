package com.example.avideo

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.avideo.crypto.VaultGateway
import com.example.avideo.navigation.AppNavGraph
import com.example.avideo.ui.CrashPanel
import com.example.avideo.ui.theme.AvideoTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /** 切到后台的时刻（`elapsedRealtime`，不受系统时间被改的影响）。0 = 当前在前台。 */
    private var backgroundedAt = 0L

    /**
     * 会话世代。递增会**重建整个导航图**（见 setContent 里的 `key`），于是回到解锁页。
     *
     * 只调 `KeyRepository.clear()` 是不够的：用户还停在列表页上，而列表页只会显示
     * 「未解锁」，返回栈里又没有解锁页 —— 会卡在一个走不出去的状态。
     */
    private val sessionEpoch = mutableStateOf(0)

    override fun onStart() {
        super.onStart()
        val since = backgroundedAt
        backgroundedAt = 0L
        // 短时间切后台不锁（< 5 分钟）；配置变更（旋屏）也是 onStop→onStart，间隔极小，同样不锁。
        if (since != 0L && SystemClock.elapsedRealtime() - since >= LOCK_TIMEOUT_MS) {
            VaultGateway.lock()
            sessionEpoch.value++
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // spec: vault-unlock「解锁界面防截屏」。
        // 只在非 debug 构建里加：debug 下截图是本项目**唯一**的反馈通道
        // （开发机没有 adb / Android SDK，真机日志只能靠截屏带回来），
        // 加上 FLAG_SECURE 就再也截不到崩溃现场了。
        // 注意它对整个 Activity 生效，release 下列表页与播放器也一并挡截屏 —— 这是想要的。
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        // 上次运行的崩溃堆栈（由 AvideoApp 的未捕获异常处理器写入）。仅 debug 读取。
        val crashFile = File(filesDir, AvideoApp.CRASH_FILE)
        val crashText = if (BuildConfig.DEBUG) {
            runCatching { crashFile.takeIf { it.isFile }?.readText() }.getOrNull()
        } else null

        setContent {
            AvideoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showCrash by remember { mutableStateOf(crashText != null) }
                    if (showCrash && crashText != null) {
                        CrashPanel(
                            text = crashText,
                            onClear = {
                                crashFile.delete()
                                showCrash = false
                            }
                        )
                    } else {
                        // 世代一变就整棵导航图重建 ⇒ 回到解锁页（spec: 后台超时后重新解锁）
                        key(sessionEpoch.value) {
                            AppNavGraph()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** spec: vault-unlock「该时长 MUST 有默认值（5 分钟）」。 */
        const val LOCK_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
