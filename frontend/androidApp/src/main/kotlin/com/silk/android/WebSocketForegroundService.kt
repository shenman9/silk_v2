package com.silk.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * WebSocket 前台服务
 * 用于在后台保持 WebSocket 连接活跃
 * 
 * 核心功能：
 * 1. 作为前台服务运行，提高应用在后台时的优先级
 * 2. 显示常驻通知，让用户知道应用正在运行
 * 3. 提供连接状态监控
 */
class WebSocketForegroundService : Service() {
    
    companion object {
        const val CHANNEL_ID = "silk_websocket_channel"
        const val CHANNEL_NAME = "Silk 连接服务"
        const val CHANNEL_DESCRIPTION = "保持 Silk 在线连接"
        const val NOTIFICATION_ID = 1001
        
        // Actions
        const val ACTION_START = "com.silk.android.action.START_WEBSOCKET"
        const val ACTION_STOP = "com.silk.android.action.STOP_WEBSOCKET"
        const val ACTION_UPDATE_STATUS = "com.silk.android.action.UPDATE_STATUS"
        
        // Extras
        const val EXTRA_STATUS = "status"
        const val EXTRA_GROUP_NAME = "group_name"
        
        // 状态
        var isConnected = false
            private set
        var currentGroupName = ""
            private set
        var currentStatus = "已连接"
            private set
            
        // 状态变化回调
        var onStatusChanged: ((String) -> Unit)? = null
        
        /**
         * 启动服务
         */
        fun start(context: Context, groupName: String = "") {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GROUP_NAME, groupName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
        
        /**
         * 更新状态
         */
        fun updateStatus(context: Context, status: String, connected: Boolean = true) {
            isConnected = connected
            currentStatus = status
            val intent = Intent(context, WebSocketForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status)
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var notificationManager: NotificationManager? = null
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentGroupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: ""
                val notification = createNotification()
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "已连接"
                updateNotification(status)
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val statusText = if (isConnected) {
            "🟢 $currentStatus"
        } else {
            "🔴 $currentStatus"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Silk${if (currentGroupName.isNotEmpty()) " - $currentGroupName" else ""}")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notification = createNotification()
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
}
