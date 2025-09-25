package com.example.aiagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun requestUserConfirmationViaNotification(
    context: Context,
    taskId: String,
    stepId: Int,
    message: String,
    timeoutMs: Long = 2 * 60_000L // 2 minutes
): Boolean = suspendCancellableCoroutine { cont ->
    val action = ConfirmationConstants.ACTION_CONFIRM_PREFIX + taskId
    val filter = IntentFilter(action)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                val result = intent?.getBooleanExtra(ConfirmationConstants.EXTRA_RESULT, false) ?: false
                if (cont.isActive) cont.resume(result)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
            }
        }
    }

    try {
        context.registerReceiver(receiver, filter)
    } catch (e: Exception) {
        if (cont.isActive) cont.resumeWithException(e)
        return@suspendCancellableCoroutine
    }

    // Post notification that opens ConfirmationActivity
    NotificationHelper.notifyConfirmation(context, taskId, stepId, message)

    // Timeout guard
    val job = GlobalScope.launch {
        delay(timeoutMs)
        if (cont.isActive) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            cont.resume(false)
        }
    }

    cont.invokeOnCancellation {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        job.cancel()
    }
}