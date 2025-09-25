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
    private const val CONFIRMATION_CHANNEL_ID = "ai_agent_confirmation"
    private val pendingConfirmations = mutableMapOf<String, (Boolean) -> Unit>()

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Regular channel
            val ch = NotificationChannel(CHANNEL_ID, "AI Agent", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            
            // Confirmation channel with higher importance
            val confirmCh = NotificationChannel(
                CONFIRMATION_CHANNEL_ID, 
                "AI Agent Confirmations", 
                NotificationManager.IMPORTANCE_HIGH
            )
            confirmCh.description = "Notifications requiring user confirmation for AI agent actions"
            nm.createNotificationChannel(confirmCh)
        }
    }

    fun createSimpleNotification(ctx: Context, title: String, text: String): android.app.Notification {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .build()
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

    fun showConfirmationNotification(
        ctx: Context,
        taskId: String,
        stepId: String,
        description: String,
        callback: (Boolean) -> Unit
    ) {
        ensureChannel(ctx)
        val confirmationKey = "${taskId}_${stepId}"
        
        // Store the callback for later use
        pendingConfirmations[confirmationKey] = callback
        
        // Create intents for approve and deny actions
        val approveIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = "APPROVE_ACTION"
            putExtra("confirmation_key", confirmationKey)
            putExtra("task_id", taskId)
            putExtra("step_id", stepId)
        }
        
        val denyIntent = Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = "DENY_ACTION"
            putExtra("confirmation_key", confirmationKey)
            putExtra("task_id", taskId)
            putExtra("step_id", stepId)
        }
        
        val approvePendingIntent = PendingIntent.getBroadcast(
            ctx, 
            confirmationKey.hashCode(), 
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val denyPendingIntent = PendingIntent.getBroadcast(
            ctx, 
            confirmationKey.hashCode() + 1, 
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create the notification
        val notification = NotificationCompat.Builder(ctx, CONFIRMATION_CHANNEL_ID)
            .setContentTitle("AI Agent Confirmation Required")
            .setContentText("$description - Approve this action?")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Approve",
                approvePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Deny",
                denyPendingIntent
            )
            .build()
        
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(confirmationKey.hashCode(), notification)
    }

    fun handleConfirmationResponse(confirmationKey: String, approved: Boolean) {
        pendingConfirmations[confirmationKey]?.let { callback ->
            callback(approved)
            pendingConfirmations.remove(confirmationKey)
        }
    }

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

        val n = NotificationCompat.Builder(ctx, CONFIRMATION_CHANNEL_ID)
            .setContentTitle("Action confirmation required")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_send, "Open to confirm", pi)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, n)
    }
}