package com.example.avideo.importing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 导入用的前台服务：只负责「让进程在写盘期间不被轻易杀掉」，业务全在 [ImportController]。
 *
 * 为什么必须是前台服务（而不是 Activity 里的协程）：spec: import-flow「导入 MUST 在能存活于
 * Activity 生命周期之外的环境中执行」「切到后台不中断」。Activity 一旋屏就重建，
 * 后台时进程也可能被回收；前台服务把这两条都挡住了。
 *
 * 注意：API 33+ 上如果没有 POST_NOTIFICATIONS 权限，系统**不会显示**这条通知
 * （只在状态栏/任务管理器里显示前台服务标记）。服务本身照常运行，所以 v1 不为此再弹一个
 * 权限请求 —— 而 manifest 里的 `foregroundServiceType="dataSync"` 是 API 34 的硬性要求。
 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch {
            try {
                ImportController.execute()
            } finally {
                stopSelf()
            }
        }
        // 不重启：导入没有断点续传（spec: import-flow「本版本不做断点续传」），
        // 进程被杀了就重来，未提交的半成品由 real_len == 0 识别并清理。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "导入进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "导入视频时的前台服务通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在导入视频")
            .setContentText("加密写入进行中，期间请不要强行关闭 App。")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "import"
        private const val NOTIFICATION_ID = 0x1A2B

        /** 由 [ImportController] 在写盘前调用。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ImportService::class.java))
        }
    }
}
