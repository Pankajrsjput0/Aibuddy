package com.example.aiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ForegroundTaskService : Service() {
    private val CHANNEL_ID = "ai_agent_fg"
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AI Agent", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val not: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Agent running")
            .setContentText("Long-running tasks in progress")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(101, not)
    }
    override fun onBind(intent: Intent?): IBinder? = null
}