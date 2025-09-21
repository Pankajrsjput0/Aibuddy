package com.example.aiagent

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class TaskExecutor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun executePlan(planJson: JSONObject) {
        scope.launch {
            val taskId = planJson.optString("task_id", "task_${System.currentTimeMillis()}")
            val planArr = planJson.getJSONArray("plan")
            val taskDir = StorageUtils.getTaskDir(context, taskId)
            val checkpointFile = File(taskDir, "checkpoint.json")
            var startIndex = readCheckpoint(checkpointFile)

            for (i in startIndex until planArr.length()) {
                val step = planArr.getJSONObject(i)
                val requires = step.optBoolean("requires_confirmation", false)
                if (requires) {
                    val ok = BiometricUtils.requestConfirmation(context, step.optString("description"))
                    if (!ok) {
                        writeCheckpoint(checkpointFile, i, "skipped_by_user")
                        return@launch
                    }
                }
                val result = executeStep(step)
                writeCheckpoint(checkpointFile, i, if (result) "done" else "failed")
                if (!result) {
                    // stop: agent can replan or use fallback later
                    return@launch
                }
            }
            writeCheckpoint(checkpointFile, planArr.length(), "completed")
        }
    }

    private fun executeStep(step: JSONObject): Boolean {
        val type = step.optString("type")
        val params = step.optJSONObject("params") ?: JSONObject()
        return when (type) {
            "open_app" -> AccessibilityController.openApp(context, params.optString("package"))
            "click" -> AccessibilityController.clickByText(context, params.optString("text"))
            "fill" -> AccessibilityController.fillText(context, params.optString("text"))
            "wait" -> {
                val ms = params.optInt("ms", 1000)
                Thread.sleep(ms.toLong()); true
            }
            "save_file" -> {
                val name = params.optString("path", "output.txt")
                val content = params.optString("content", "")
                val file = File(context.filesDir, name)
                file.parentFile?.mkdirs()
                file.writeText(content)
                true
            }
            "generate_text" -> {
                // call OpenRouter for generation (simple call)
                val prompt = params.optString("prompt")
                val saveAs = params.optString("save_as", "output.txt")
                return runBlocking {
                    try {
                        val apiKey = StorageUtils.getOpenRouterKey(context) ?: return@runBlocking false
                        val json = OpenRouterClient.requestPlanner(apiKey, prompt)
                        val text = json.optString("generated_text", json.toString())
                        val f = File(context.filesDir, saveAs)
                        f.parentFile?.mkdirs()
                        f.writeText(text)
                        true
                    } catch (e: Exception) { e.printStackTrace(); false }
                }
            }
            else -> false
        }
    }

    private fun readCheckpoint(file: File): Int {
        if (!file.exists()) return 0
        return try {
            val txt = file.readText()
            val j = JSONObject(txt)
            j.optInt("last_step", 0)
        } catch (e: Exception) { 0 }
    }

    private fun writeCheckpoint(file: File, stepIndex: Int, status: String) {
        val j = JSONObject()
        j.put("last_step", stepIndex)
        j.put("status", status)
        file.writeText(j.toString())
    }
}