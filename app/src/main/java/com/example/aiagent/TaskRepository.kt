package com.example.aiagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object TaskRepository {

    data class TaskItem(val id: String, val dir: File, val checkpointFile: File, val status: String)

    suspend fun listTasks(context: Context): List<TaskItem> = withContext(Dispatchers.IO) {
        val base = File(context.filesDir, "tasks")
        if (!base.exists()) return@withContext emptyList()
        return@withContext base.listFiles()?.mapNotNull { dir ->
            val cp = File(dir, "checkpoint.json")
            val status = if (cp.exists()) {
                try { org.json.JSONObject(cp.readText()).optString("status","unknown") } catch (e: Exception) { "unknown" }
            } else "not_started"
            TaskItem(dir.name, dir, cp, status)
        } ?: emptyList()
    }

    fun pauseTask(context: Context, task: TaskItem) {
        // write a pause flag file
        File(task.dir, "pause.flag").writeText("paused")
        NotificationHelper.notify(context, "Task paused", task.id)
    }

    fun resumeTask(context: Context, task: TaskItem) {
        File(task.dir, "pause.flag").delete()
        NotificationHelper.notify(context, "Task resumed", task.id)
        // You may also trigger TaskExecutor to re-run the plan
    }

    fun abortTask(context: Context, task: TaskItem) {
        File(task.dir, "abort.flag").writeText("abort")
        NotificationHelper.notify(context, "Abort signal sent", task.id)
    }
}