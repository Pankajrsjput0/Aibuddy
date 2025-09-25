package com.example.aiagent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LongTaskWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString("taskId") ?: return@withContext Result.failure()
        val planJsonString = inputData.getString("planJson") ?: return@withContext Result.failure()
        try {
            val planJson = JSONObject(planJsonString)
            val executor = TaskExecutor(applicationContext)
            executor.executePlan(planJson) // ensure this queues/blocks appropriately
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}