package com.example.avideo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.avideo.navigation.AppNavGraph
import com.example.avideo.ui.CrashPanel
import com.example.avideo.ui.theme.AvideoTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 上次运行的崩溃堆栈（由 AvideoApp 的未捕获异常处理器写入）。仅 debug 读取。
        // 理由：本开发机没有 SDK / adb，拿不到 logcat —— 把堆栈画到屏幕上是唯一可行的诊断通道。
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
                        AppNavGraph()
                    }
                }
            }
        }
    }
}
