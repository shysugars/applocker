package com.example.helloworld.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.helloworld.R

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "keep_alive_channel"
        const val NOTIFICATION_ID = 999
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 创建通知并启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // START_STICKY 告诉系统如果内存不足杀死了服务，内存恢复后要重启它
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_MIN 是关键，这使得通知在状态栏上不显示图标，折叠在通知抽屉底部
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台保活服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持应用在后台运行以监控应用状态"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("应用暂停服务运行中")
            .setContentText("正在保护后台状态...")
            .setSmallIcon(R.mipmap.ic_launcher) // 确保这里有图标
            .setPriority(NotificationCompat.PRIORITY_MIN) // 兼容旧版本 Android
            .setOngoing(true) // 禁止用户侧滑删除
            .build()
    }
}