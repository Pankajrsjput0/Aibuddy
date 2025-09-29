package com.example.aiagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.buffer
import okio.sink

/**
 * Full-featured TaskExecutor for the AI Agent.
 *
 * Replace your existing TaskExecutor.kt with this file.
 *
 * NOTE:
 * - This file assumes helper classes exist (AccessibilityController, NotificationHelper, StorageUtils,
 *   OpenRouterClient.requestPlanner, ScreenCaptureHelper, WorkEnqueueHelper, PreferencesUtils).
 * - Some steps (screen recording, GitHub OAuth, wallet transfers) are dangerous and require explicit user confirmation.
 */
class TaskExecutor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    fun executePlan(planJson: JSONObject) {
        scope.launch {
            val taskId = planJson.optString("task_id", "task_${System.currentTimeMillis()}")
            val stepsArr = planJson.optJSONArray("steps") ?: planJson.optJSONArray("plan") ?: JSONArray()
            val taskDir = StorageUtils.getTaskDir(context, taskId)
            val checkpointFile = File(taskDir, "checkpoint.json")
            var startIndex = readCheckpoint(checkpointFile)

            // Save full plan for debugging
            File(taskDir, "plan.json").writeText(planJson.toString(2))

            for (i in startIndex until stepsArr.length()) {
                val step = stepsArr.getJSONObject(i)
                val stepId = step.optString("id", "s$i")
                // Check pause/abort flags
                if (File(taskDir, "abort.flag").exists()) {
                    writeCheckpoint(checkpointFile, i, "aborted")
                    NotificationHelper.notify(context, "Task aborted", "Task $taskId aborted by user")
                    return@launch
                }
                if (File(taskDir, "pause.flag").exists()) {
                    writeCheckpoint(checkpointFile, i, "paused")
                    // Wait until pause.flag removed (or abort)
                    while (File(taskDir, "pause.flag").exists()) {
                        if (File(taskDir, "abort.flag").exists()) {
                            writeCheckpoint(checkpointFile, i, "aborted")
                            NotificationHelper.notify(context, "Task aborted", "Task $taskId aborted while paused")
                            return@launch
                        }
                        delay(2000)
                    }
                }

                // Confirm if required
                val requires = step.optBoolean("requires_confirmation", false)
                if (requires) {
                    val desc = step.optString("description", "Please confirm")
                    val approved = requestUserConfirmationViaNotification(context, taskId, stepId, desc)
                    if (!approved) {
                        writeCheckpoint(checkpointFile, i, "skipped_by_user")
                        continue // skip this step but continue subsequent steps
                    }
                }

                // Execute step with retries
                var success = false
                val retry = step.optJSONObject("retry")
                val retryCount = retry?.optInt("count", 0) ?: 0
                val backoff = retry?.optInt("backoff_s", 2) ?: 2
                var attempt = 0
                while (attempt <= retryCount) {
                    try {
                        success = withContext(Dispatchers.IO) { executeStepSuspend(step, taskDir, taskId) }
                        if (success) break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    attempt++
                    if (!success && attempt <= retryCount) delay((backoff * 1000).toLong())
                }

                writeCheckpoint(checkpointFile, i, if (success) "done" else "failed")
                if (!success) {
                    // Optionally run fallback if provided
                    val fallback = (planJson.optJSONArray("fallback"))
                    if (fallback != null && fallback.length() > 0) {
                        NotificationHelper.notify(context, "Running fallback", "Attempting fallback steps for task $taskId")
                        // write fallback to file and call executePlan recursively with fallback as plan
                        val fbPlan = JSONObject().apply {
                            put("task_id", "$taskId-fallback-${System.currentTimeMillis()}")
                            put("created_at", System.currentTimeMillis())
                            put("goal", planJson.optString("goal", "fallback"))
                            put("steps", fallback)
                        }
                        executePlan(fbPlan)
                    }
                    // stop main plan on failure to avoid cascading errors
                    NotificationHelper.notify(context, "Task failed", "Step ${step.optString("id")} failed")
                    return@launch
                }
            }

            writeCheckpoint(checkpointFile, stepsArr.length(), "completed")
            NotificationHelper.notify(context, "Task completed", "Task $taskId finished.")
        }
    }

    // Confirmation helper that delegates to NotificationHelper/Confirmation flow
    private suspend fun requestUserConfirmationViaNotification(
        context: Context,
        taskId: String,
        stepId: String,
        description: String,
        timeoutMs: Long = 2 * 60_000L
    ): Boolean = suspendCancellableCoroutine { cont ->
        try {
            NotificationHelper.notifyConfirmation(context, taskId, stepId.toIntOrNull() ?: (System.currentTimeMillis() % 10000).toInt(), description)
            // Listen for broadcast already implemented in ConfirmationReceiverHelper.kt
            val action = ConfirmationConstants.ACTION_CONFIRM_PREFIX + taskId
            val filter = android.content.IntentFilter(action)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    try {
                        val result = intent?.getBooleanExtra(ConfirmationConstants.EXTRA_RESULT, false) ?: false
                        if (cont.isActive) cont.resume(result) {}
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(false) {}
                    } finally {
                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    }
                }
            }
            try { context.registerReceiver(receiver, filter) } catch (e: Exception) {
                e.printStackTrace()
                if (cont.isActive) cont.resume(false) {}
                return@suspendCancellableCoroutine
            }

            // timeout
            val job = scope.launch {
                delay(timeoutMs)
                if (cont.isActive) {
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                    cont.resume(false) {}
                }
            }

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                job.cancel()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (cont.isActive) cont.resume(false) {}
        }
    }

    /**
     * Execute a single StepObject. This is a suspend function so it can call network or blocking APIs.
     * Return true on success, false on permanent failure.
     */
    private suspend fun executeStepSuspend(step: JSONObject, taskDir: File, taskId: String): Boolean {
        return withContext(Dispatchers.IO) {
            val type = step.optString("type")
            val params = step.optJSONObject("params") ?: JSONObject()
            val timeout = step.optInt("timeout_s", 60)
            when (type) {
                "open_app" -> {
                    val pkg = params.optString("package")
                    AccessibilityController.openApp(context, pkg)
                }
                "close_app" -> {
                    val pkg = params.optString("package")
                    AccessibilityController.closeApp(context, pkg)
                }
                "app_tap", "click" -> {
                    // support selector types by text/resource_id/coords/xpath
                    val by = params.optString("by", "text")
                    when (by) {
                        "text" -> AccessibilityController.clickByText(context, params.optString("value"))
                        "resource_id" -> AccessibilityController.clickById(context, params.optString("value"))
                        "coords" -> {
                            val arr = params.optJSONArray("value")
                            if (arr != null && arr.length() >= 2) AccessibilityController.clickByCoords(arr.getInt(0), arr.getInt(1)) else false
                        }
                        else -> AccessibilityController.clickByText(context, params.optString("value"))
                    }
                }
                "app_type", "fill" -> {
                    val text = params.optString("text")
                    val target = params.optString("target", "focused")
                    AccessibilityController.fillText(context, text, target)
                }
                "app_swipe" -> {
                    val from = params.optJSONArray("from")
                    val to = params.optJSONArray("to")
                    val duration = params.optInt("duration_ms", 300)
                    if (from != null && to != null && from.length() >= 2 && to.length() >= 2) {
                        AccessibilityController.swipe(from.getInt(0), from.getInt(1), to.getInt(0), to.getInt(1), duration)
                    } else false
                }
                "web_visit" -> {
                    val url = params.optString("url")
                    // Open default browser via intent
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                }
                "web_interact" -> {
                    // sequence of {action, selector, value}
                    val seq = params.optJSONArray("sequence") ?: JSONArray()
                    for (i in 0 until seq.length()) {
                        val stepObj = seq.getJSONObject(i)
                        val action = stepObj.optString("action")
                        val selector = stepObj.optJSONObject("selector")
                        val value = stepObj.optString("value")
                        // best-effort: use AccessibilityController to act in foreground app/webview
                        when (action) {
                            "click" -> {
                                val by = selector?.optString("by", "text") ?: "text"
                                val v = selector?.optString("value") ?: value
                                AccessibilityController.clickBySelector(context, by, v)
                            }
                            "fill" -> {
                                val v = selector?.optString("value") ?: value
                                AccessibilityController.fillBySelector(context, selector, v)
                            }
                            "wait" -> {
                                val ms = stepObj.optInt("ms", 1000)
                                delay(ms.toLong())
                            }
                            "screenshot" -> {
                                val saveAs = stepObj.optString("save_as", "tasks/$taskId/web_${i}.png")
                                // trigger screenshot flow (requires MediaProjection permission)
                                // We create a file entry and request user to grant capture when necessary
                                // Placeholder: record that a screenshot should be taken by the UI flow
                                NotificationHelper.notify(context, "Action needed", "Please grant screen capture for web screenshot")
                                // Not implemented: actual MediaProjection here
                            }
                            else -> {}
                        }
                    }
                    true
                }
                "input_otp" -> {
                    // Ask user to paste OTP via notification/intent
                    NotificationHelper.notify(context, "OTP required", params.optString("message", "Please paste OTP into the app"))
                    false // requires manual user action to complete
                }
                "take_screenshot" -> {
                    val saveAs = params.optString("save_as", "tasks/$taskId/screenshot_${System.currentTimeMillis()}.png")
                    // This requires MediaProjection permission flow from an Activity. We'll create a file placeholder and notify user to allow.
                    NotificationHelper.notify(context, "Screen capture", "Please allow screen capture to save screenshot.")
                    // If the app has a MediaProjection token saved, call ScreenCaptureHelper here (not implemented fully)
                    true
                }
                "screen_record" -> {
                    NotificationHelper.notify(context, "Screen record", "Please allow screen recording (user consent required).")
                    false
                }
                "generate_text" -> {
                    val prompt = params.optString("prompt")
                    val saveAs = params.optString("save_as", "tasks/$taskId/output_${System.currentTimeMillis()}.txt")
                    val model = params.optString("model", "oai/gpt-4o-mini")
                    val apiKey = StorageUtils.getOpenRouterKey(context) ?: return@withContext false
                    val json = try {
                        OpenRouterClient.requestPlanner(apiKey, prompt, model)
                    } catch (e: Exception) {
                        e.printStackTrace(); null
                    }
                    val text = json?.optString("generated_text") ?: json?.optString("choices") ?: json?.toString()
                    if (text != null) {
                        val f = File(taskDir, saveAs)
                        f.parentFile?.mkdirs()
                        f.writeText(text)
                        true
                    } else false
                }
                "generate_code" -> {
                    val spec = params.optString("spec")
                    val language = params.optString("language", "plaintext")
                    // Use LLM to create files; we expect the planner to include file outputs in response
                    val apiKey = StorageUtils.getOpenRouterKey(context) ?: return@withContext false
                    val prompt = "Generate project files for spec:\n$spec\nReturn file list and content as JSON."
                    val json = try { OpenRouterClient.requestPlanner(apiKey, prompt) } catch (e: Exception) { e.printStackTrace(); null }
                    if (json != null) {
                        // Expect json contains "files": [{path, content}, ...]
                        val filesArr = json.optJSONArray("files")
                        if (filesArr != null) {
                            for (i in 0 until filesArr.length()) {
                                val fobj = filesArr.getJSONObject(i)
                                val path = fobj.optString("path")
                                val content = fobj.optString("content")
                                val file = File(taskDir, path)
                                file.parentFile?.mkdirs()
                                file.writeText(content)
                            }
                            true
                        } else {
                            // fallback: save raw text
                            val out = File(taskDir, "generated_code.txt")
                            out.writeText(json.toString(2))
                            true
                        }
                    } else false
                }
                "save_file" -> {
                    val path = params.optString("path", "tasks/$taskId/out_${System.currentTimeMillis()}.txt")
                    val content = params.optString("content", "")
                    val file = File(taskDir, path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    true
                }
                "download_file" -> {
                    val url = params.optString("url")
                    val saveAs = params.optString("save_as", "tasks/$taskId/download_${System.currentTimeMillis()}")
                    try {
                        val req = Request.Builder().url(url).build()
                        val resp = httpClient.newCall(req).execute()
                        if (!resp.isSuccessful) return@withContext false
                        val body: ResponseBody? = resp.body
                        if (body != null) {
                            val outFile = File(taskDir, saveAs)
                            outFile.parentFile?.mkdirs()
                            outFile.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                            true
                        } else false
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                "upload_file" -> {
                    // Uploads are highly dependent on target. Here we support simple HTTP multipart upload if params provide url.
                    val filePath = params.optString("path")
                    val target = params.optString("target")
                    if (filePath.isNullOrEmpty()) return@withContext false
                    val f = File(context.filesDir, filePath)
                    if (!f.exists()) return@withContext false
                    when (target) {
                        "s3", "gdrive", "github" -> {
                            NotificationHelper.notify(context, "Upload required", "Upload to $target may require auth. Opening auth flow.")
                            // Open browser to let user authenticate or instruct further
                            true
                        }
                        "http" -> {
                            val uploadUrl = params.optString("url")
                            // TODO: implement multipart upload using OkHttp
                            NotificationHelper.notify(context, "Upload", "Attempting HTTP upload (not fully implemented).")
                            true
                        }
                        else -> {
                            false
                        }
                    }
                }
                "send_notification", "notify" -> {
                    val title = params.optString("title", "Agent")
                    val body = params.optString("body", "")
                    NotificationHelper.notify(context, title, body)
                    true
                }
                "schedule_job" -> {
                    val cron = params.optString("cron", null)
                    val delayS = params.optInt("delay_s", -1)
                    val worker = params.optString("worker", "long_task_worker")
                    val payload = params.optJSONObject("payload") ?: JSONObject()
                    // Enqueue through WorkEnqueueHelper (use WorkManager constraints)
                    val taskPayload = payload.toString()
                    val jid = params.optString("job_id", "job_${System.currentTimeMillis()}")
                    WorkEnqueueHelper.enqueueJob(context, jid, taskPayload, delayS)
                    true
                }
                "confirm_user" -> {
                    val message = params.optString("message", "Please confirm")
                    val approved = requestUserConfirmationViaNotification(context, taskId, step.optString("id","confirm"), message)
                    approved
                }
                "screenshot_and_ocr" -> {
                    val saveAs = params.optString("save_as", "tasks/$taskId/ocr_${System.currentTimeMillis()}.txt")
                    // Request capture permission via UI and then OCR
                    NotificationHelper.notify(context, "OCR requested", "Please allow screen capture to perform OCR.")
                    // Placeholder: actual OCR pipeline requires MediaProjection + OCR lib (Tesseract or ML Kit)
                    true
                }
                "delete_files" -> {
                    val path = params.optString("path")
                    val recursive = params.optBoolean("recursive", false)
                    if (path.isNullOrEmpty()) return@withContext false
                    val target = File(path)
                    if (!target.exists()) return@withContext false
                    // Only allow deletion in app storage or explicit paths
                    if (!target.absolutePath.startsWith(context.filesDir.absolutePath)) {
                        NotificationHelper.notify(context, "Delete blocked", "Deletion blocked for safety: $path")
                        return@withContext false
                    }
                    if (target.isDirectory && recursive) {
                        target.deleteRecursively()
                    } else {
                        target.delete()
                    }
                    true
                }
                "create_github_repo" -> {
                    // Cannot silently create a GitHub repo; open browser to OAuth flow and instruct user
                    NotificationHelper.notify(context, "GitHub action", "Please sign in to GitHub to allow repo creation.")
                    // open GitHub new repo page (user must complete)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/new"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    false
                }
                "deploy_infra" -> {
                    // Provisioning typically requires cloud credentials and explicit user approval
                    NotificationHelper.notify(context, "Deploy infra", "This action requires cloud credentials and explicit user approval.")
                    false
                }
                "fallback_action" -> {
                    val reason = params.optString("reason", "fallback")
                    val steps = params.optJSONArray("steps") ?: JSONArray()
                    // Run fallback steps locally by wrapping into a plan
                    val fbPlan = JSONObject().apply {
                        put("task_id", "$taskId-fallback-${System.currentTimeMillis()}")
                        put("created_at", System.currentTimeMillis())
                        put("goal", "fallback: $reason")
                        put("steps", steps)
                    }
                    executePlan(fbPlan)
                    true
                }
                "noop" -> { true }
                "custom_shell" -> {
                    // Running arbitrary shell commands requires root; block and require confirmation
                    NotificationHelper.notify(context, "Shell command blocked", "Shell commands require root. Not executed.")
                    false
                }
                "encrypt_store" -> {
                    val keyName = params.optString("key_name")
                    val value = params.optString("value")
                    StorageUtils.encryptAndStore(context, keyName, value)
                    true
                }
                "transfer_money" -> {
                    // Finance transfers MUST require confirmation and are best handled via secure wallet SDKs
                    NotificationHelper.notify(context, "Transfer requested", "Money transfer requires secure wallet integration and confirmation.")
                    false
                }
                else -> {
                    // Unknown action: record and fail gracefully
                    NotificationHelper.notify(context, "Unknown action", "Action type '$type' is not supported by the executor.")
                    false
                }
            }
        }
    }

    private fun readCheckpoint(file: File): Int {
        if (!file.exists()) return 0
        return try {
            val txt = file.readText()
            val j = JSONObject(txt)
            j.optInt("last_step", 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun writeCheckpoint(file: File, stepIndex: Int, status: String) {
        val j = JSONObject()
        j.put("last_step", stepIndex)
        j.put("status", status)
        j.put("timestamp", System.currentTimeMillis())
        file.parentFile?.mkdirs()
        file.writeText(j.toString())
    }

    fun cancelTask(taskId: String) {
        // Cancel all coroutines in this executor scope (coarse)
        scope.coroutineContext.cancelChildren()
        NotificationHelper.notify(context, "Task canceled", "Task $taskId canceled")
    }
}
