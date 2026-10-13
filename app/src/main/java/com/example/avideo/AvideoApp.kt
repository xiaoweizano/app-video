package com.example.avideo

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 未捕获异常的堆栈记录（**仅 debug 构建**）。
 *
 * 为什么需要它：这台开发机**没有 Android SDK / adb**（见 TODOS.md 的环境说明），拿不到 logcat。
 * 于是把堆栈写成**两份**：
 *  1. `filesDir/crash.log` —— 下次启动时由 MainActivity 用 CrashPanel 显示在屏幕上（可截图/长按复制）；
 *  2. **「下载」目录里的 `avideo-crash-<时间戳>.txt`** —— 任何文件管理器都能看见，
 *     可以直接发给开发。这一份是为了绕开「截图很长、被压缩后文字不可读」的问题。
 *
 * release 构建不启用：不该把堆栈暴露给用户。
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
            val report = renderReport(thread, throwable)
            // 两条记录路径都要吞掉自身异常：记录失败不能掩盖原始崩溃
            runCatching { File(filesDir, CRASH_FILE).writeText(report) }
            runCatching { exportToDownloads(report) }
            // 交回系统，保持正常的崩溃流程（系统仍会弹「已停止运行」）
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun renderReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return buildString {
            appendLine("thread = ${thread.name}")
            appendLine("app = ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device = ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT} / ${Build.VERSION.RELEASE}")
            appendLine()
            append(sw.toString())
        }
    }

    /**
     * 往系统「下载」目录写一份。用 MediaStore 而不是直接写路径：API 29+ 的分区存储下
     * 这样不需要任何存储权限，用户也能在「文件管理 → 下载」里直接看到。
     */
    private fun exportToDownloads(report: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "avideo-crash-${System.currentTimeMillis()}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) }
    }

    companion object {
        const val CRASH_FILE = "crash.log"
    }
}
