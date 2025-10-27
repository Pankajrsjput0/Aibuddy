package com.example.aiagent

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        val confirmationKey = intent.getStringExtra("confirmation_key") ?: return

        when (action) {
            "APPROVE_ACTION" -> {
                NotificationHelper.handleConfirmationResponse(confirmationKey, true)
            }
            "DENY_ACTION" -> {
                NotificationHelper.handleConfirmationResponse(confirmationKey, false)
            }
        }

        // Cancel the notification
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(confirmationKey.hashCode())
    }
}
