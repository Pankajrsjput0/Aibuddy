package com.example.aiagent

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

fun enqueueLongTask(context: Context, taskId: String, planJson: String) {
    val wifiOnly = PreferencesUtils.getBool(context, "pref_wifi_only", true)
    val chargingOnly = PreferencesUtils.getBool(context, "pref_charging_only", false)

    val constraintsBuilder = Constraints.Builder()
    if (wifiOnly) constraintsBuilder.setRequiredNetworkType(NetworkType.UNMETERED)
    else constraintsBuilder.setRequiredNetworkType(NetworkType.CONNECTED)
    if (chargingOnly) constraintsBuilder.setRequiresCharging(true)

    val input = workDataOf("taskId" to taskId, "planJson" to planJson)
    val work = OneTimeWorkRequestBuilder<LongTaskWorker>()
        .setConstraints(constraintsBuilder.build())
        .setInputData(input)
        .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(taskId, ExistingWorkPolicy.KEEP, work)
}

object WorkEnqueueHelper {
    fun enqueueJob(context: Context, workerName: String, payloadJson: String, delaySeconds: Int) {
        val inputData = Data.Builder()
            .putString("payload", payloadJson)
            .build()

        val requestBuilder = OneTimeWorkRequestBuilder<GenericWorker>()
            .setInputData(inputData)

        if (delaySeconds > 0) {
            requestBuilder.setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
        }

        val request = requestBuilder.build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workerName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

class GenericWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val payload = inputData.getString("payload") ?: return Result.failure()
        // Log or process payload
        android.util.Log.d("GenericWorker", "Processing payload: $payload")
        return Result.success()
    }
}