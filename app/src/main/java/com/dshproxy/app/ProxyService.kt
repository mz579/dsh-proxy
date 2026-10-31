package com.dshproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class ProxyService : Service() {

    private var server: ProxyServer? = null
    private var store: ProviderStore? = null

    /** 安全取配置：store 不可用时返回默认配置（空 baseUrl，startProxy 会提示未配置）。 */
    private fun safeConfig(): ProxyConfig =
        try { store?.load() ?: ProxyConfig(apiKey = "") } catch (_: Throwable) { ProxyConfig(apiKey = "") }

    override fun onCreate() {
        super.onCreate()
        try {
            store = ProviderStore(this)
        } catch (e: Throwable) {
            // 极端情况：存储初始化彻底失败。不能让整个服务崩掉，
            // 否则「打开 App 即自启」也会连带失败。用空配置继续（startProxy 会提示未配置）。
            ProxyLog.add("存储初始化失败：${e.message}")
        }
        createChannel()
        // Android 14+ 必须先 startForeground 再干活，否则 ANR/崩溃。
        // 类型必须与清单里 foregroundServiceType 完全一致。
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("启动中…"), type)
        } catch (e: Throwable) {
            // 例如 Android 12+ 后台启动前台服务被拒（ForegroundServiceStartNotAllowedException）。
            ProxyLog.add("前台服务启动受阻：${e.message}")
            running = false
            lastError = e.message
            stopSelf()
            return
        }
        startProxy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null) startProxy()
        // START_STICKY：被系统回收后自动重启。但若用户点了「停止」，stopService 会走 onDestroy，
        // 不会被这里拉起。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉时，别把代理一起带走（用户可能只是清理界面）。
        // 若确实想停，用 App 内的「停止」按钮。
        super.onTaskRemoved(rootIntent)
    }

    @Synchronized
    private fun startProxy() {
        stopProxy()
        try {
            val cfg = safeConfig()
            if (cfg.baseUrl.isBlank()) {
                running = false
                update("未配置 Base URL")
                return
            }
            val s = ProxyServer(cfg.port) { safeConfig() }
            s.startServer()
            server = s
            running = true
            lastError = null
            update("运行中 127.0.0.1:${cfg.port}/v1 → ${cfg.baseUrl}")
        } catch (e: Exception) {
            running = false
            lastError = e.message
            update("启动失败：${e.message}")
            ProxyLog.add("启动失败：${e.message}")
        }
    }

    @Synchronized
    private fun stopProxy() {
        try {
            server?.stopServer()
        } catch (_: Exception) {
        }
        server = null
        running = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DSH 代理")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    private fun update(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_ID = "dsh_proxy"
        const val NOTIF_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastError: String? = null

        /**
         * 用户本次进程内是否手动点过「停止」。
         * 仅内存标记：进程重启（含手机重启）自动清零，避免跨重启残留导致「不再自启」。
         */
        @Volatile
        var userStopped: Boolean = false

        fun start(context: Context) {
            val i = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
