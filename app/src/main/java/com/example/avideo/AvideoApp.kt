package com.example.avideo

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 未捕获异常的堆栈记录 + 崩溃显示（**仅 debug 构建**）。
 *
 * 为什么需要它：这台开发机**没有 Android SDK / adb**（见 TODOS.md 的环境说明），
 * 所以拿不到 logcat。把崩溃栈写到文件、下次启动时显示在屏幕上，是唯一不需要任何
 * 外部工具的诊断通道 —— 出问题截个图就能读。
 *
 * release 构建不启用：不该把堆栈显示给用户。
 */
class AvideoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
    }

    private fun installCrashRecorder() {
        if (!BuildConfig.DEBUG) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val header = buildString {
                    appendLine("thread = ${thread.name}")
                    appendLine("app version = ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine()
                }
                File(filesDir, CRASH_FILE).writeText(header + sw.toString())
            } catch (_: Throwable) {
                // 记录失败不能掩盖原始崩溃：忽略并继续交给系统处理
            }
            // 交回系统，保持正常的崩溃流程（系统仍会弹出「已停止运行」）
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "crash.log"
    }
}
