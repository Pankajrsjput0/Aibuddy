package com.example.aiagent

import android.content.Context
import androidx.work.*

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