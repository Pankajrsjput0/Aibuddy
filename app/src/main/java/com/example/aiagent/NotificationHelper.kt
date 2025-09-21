package com.example.aiagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "ai_agent_channel"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AI Agent", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    fun startForegroundService(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
        val s = Intent(ctx, ForegroundTaskService::class.java)
        ctx.startForegroundService(s)
        // ForegroundTaskService will post its own notification; this is just a helper
    }

    fun notify(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), n)
    }
}






// existing imports & ensureChannel() present

fun notifyConfirmation(
    ctx: Context,
    taskId: String,
    stepId: Int,
    message: String,
    notifId: Int = (System.currentTimeMillis() % 10000).toInt()
) {
    ensureChannel(ctx)
    val intent = Intent(ctx, ConfirmationActivity::class.java).apply {
        putExtra(ConfirmationConstants.EXTRA_TASK_ID, taskId)
        putExtra(ConfirmationConstants.EXTRA_STEP_ID, stepId)
        putExtra(ConfirmationConstants.EXTRA_MESSAGE, message)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pi = PendingIntent.getActivity(
        ctx, notifId, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val n = NotificationCompat.Builder(ctx, "ai_agent_channel")
        .setContentTitle("Action confirmation required")
        .setContentText(message)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .addAction(android.R.drawable.ic_menu_send, "Open to confirm", pi)
        .build()

    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    nm.notify(notifId, n)
}