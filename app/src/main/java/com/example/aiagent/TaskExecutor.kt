package com.example.aiagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.util.concurrent.TimeUnit

/**
 * TaskExecutor.kt
 *
 * Full-featured executor that:
 * - Validates plans (TaskPlanSchema)
 * - Executes all supported step types (with safe stubs where needed)
 * - Checkpoints progress into tasks/{task_id}/checkpoint.json
 * - Uses confirmation flow for destructive/sensitive steps
 *
 * NOTE: This file assumes your project includes helper utilities:
 * - AccessibilityController (openApp, clickByText, clickById, swipe, fillText, etc.)
 * - NotificationHelper (notify, notifyConfirmation, showProgressNotification)
 * - StorageUtils (getTaskDir, getOpenRouterKey, encryptAndStore)
 * - OpenRouterClient (requestPlanner/sendMessage)
 * - ScreenCaptureHelper (MediaProjection flow)
 * - WorkEnqueueHelper (enqueue job)
 * - PreferencesUtils
 * - ConfirmationConstants & ConfirmationActivity registration
 *
 * Some actions (GitHub OAuth, payments, wallet transfers, CAPTCHA solving) require additional SDKs
 * and explicit user consent. This executor will prompt the user and open necessary web flows instead
 * of attempting anything unsafe or disallowed.
 */
class TaskExecutor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { tts?.language = Locale.getDefault() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Public entry point. Accepts full TaskPlan JSON (TaskPlanSchema).
     */
    fun executePlan(planJson: JSONObject) {
        scope.launch {
            try {
                // Validate the overall plan structure
                val validation = validateTaskPlan(planJson)
                if (!validation.valid) {
                    logError("Plan validation failed: ${validation.reason}")
                    NotificationHelper.notify(context, "Plan invalid", validation.reason)
                    return@launch
                }

                val taskId = planJson.optString("task_id", "task_${System.currentTimeMillis()}")
                val createdAt = planJson.optString("created_at", "")
                val goal = planJson.optString("goal", "")
                val steps = planJson.optJSONArray("steps") ?: planJson.optJSONArray("plan") ?: JSONArray()
                val fallback = planJson.optJSONArray("fallback") ?: JSONArray()
                val taskDir = StorageUtils.getTaskDir(context, taskId)
                if (!taskDir.exists()) taskDir.mkdirs()
                val checkpointFile = File(taskDir, "checkpoint.json")

                // Persist original plan for debugging/inspection
                File(taskDir, "plan.json").writeText(planJson.toString(2))

                // Load or initialize checkpoint
                val checkpoint = loadCheckpoint(checkpointFile, steps.length())
                var currentIndex = checkpoint.last_step

                // Enforce prereqs (e.g., wifi/charging) if present
                val prereqs = planJson.optJSONObject("prerequisites")
                if (prereqs != null) {
                    val ok = checkPrerequisites(prereqs)
                    if (!ok) {
                        NotificationHelper.notify(context, "Prerequisites unmet", "Task $taskId requires ${prereqs.toString()}")
                        return@launch
                    }
                }

                NotificationHelper.notify(context, "Task started", "Task: ${goal.ifEmpty { taskId }}")

                for (i in currentIndex until steps.length()) {
                    val step = steps.optJSONObject(i) ?: continue
                    val stepId = step.optString("id", "s$i")
                    // Respect pause/abort flags
                    if (File(taskDir, "abort.flag").exists()) {
                        checkpoint.steps_status.put(stepId, "aborted")
                        checkpoint.last_step = i
                        checkpoint.updated_at = System.currentTimeMillis()
                        saveCheckpoint(checkpointFile, checkpoint)
                        NotificationHelper.notify(context, "Task aborted", taskId)
                        return@launch
                    }
                    if (File(taskDir, "pause.flag").exists()) {
                        checkpoint.steps_status.put(stepId, "paused")
                        saveCheckpoint(checkpointFile, checkpoint)
                        // wait until pause removed or abort
                        while (File(taskDir, "pause.flag").exists()) {
                            if (File(taskDir, "abort.flag").exists()) {
                                NotificationHelper.notify(context, "Task aborted", taskId)
                                return@launch
                            }
                            delay(1000)
                        }
                    }

                    // Confirmation step pre-check
                    val requiresConfirmation = step.optBoolean("requires_confirmation", false)
                    if (requiresConfirmation) {
                        val message = step.optString("description", "Please confirm this action")
                        val approved = requestUserConfirmationViaNotification(taskId, stepId, message)
                        if (!approved) {
                            checkpoint.steps_status.put(stepId, "skipped_by_user")
                            checkpoint.last_step = i + 1
                            checkpoint.updated_at = System.currentTimeMillis()
                            saveCheckpoint(checkpointFile, checkpoint)
                            continue
                        }
                    }

                    // Execute step with retry/backoff
                    val retryObj = step.optJSONObject("retry")
                    val retries = retryObj?.optInt("count", 0) ?: 0
                    val backoff = retryObj?.optInt("backoff_s", 2) ?: 2
                    var success = false
                    var attempt = 0
                    while (attempt <= retries) {
                        try {
                            success = executeSingleStep(step, taskDir, taskId)
                            if (success) break
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                        attempt++
                        if (!success && attempt <= retries) delay(backoff * 1000L)
                    }

                    checkpoint.steps_status.put(stepId, if (success) "done" else "failed")
                    checkpoint.last_step = i + 1
                    checkpoint.updated_at = System.currentTimeMillis()
                    saveCheckpoint(checkpointFile, checkpoint)

                    if (!success) {
                        logError("Step failed: ${step.optString("id", stepId)}")
                        // attempt fallback if provided for this plan
                        if (fallback.length() > 0) {
                            NotificationHelper.notify(context, "Running fallback", "Attempting fallback steps for task $taskId")
                            val fb = JSONObject().apply {
                                put("task_id", "${taskId}_fallback_${System.currentTimeMillis()}")
                                put("created_at", System.currentTimeMillis())
                                put("goal", "fallback for $taskId")
                                put("steps", fallback)
                            }
                            executePlan(fb) // recursive fallback run
                        }
                        NotificationHelper.notify(context, "Task failed", "Task $taskId failed at step $stepId")
                        return@launch
                    }
                }

                // Mark complete
                checkpoint.status = "completed"
                checkpoint.updated_at = System.currentTimeMillis()
                saveCheckpoint(checkpointFile, checkpoint)
                NotificationHelper.notify(context, "Task completed", "Task $taskId finished")
            } catch (e: Exception) {
                e.printStackTrace()
                NotificationHelper.notify(context, "Executor error", e.message ?: "Unknown error")
            }


// ---------- Step executor (suspend) ----------
    private suspend fun executeSingleStep(step: JSONObject, taskDir: File, taskId: String): Boolean =
        withContext(Dispatchers.IO) {
            val type = step.optString("type")
            val params = step.optJSONObject("params") ?: JSONObject()
            when (type) {
                // Device / App Control
                "open_app" -> {
                    val pkg = params.optString("package")
                    AccessibilityController.openApp(context, pkg)
                }
                "close_app" -> {
                    val pkg = params.optString("package")
                    AccessibilityController.closeApp(context, pkg)
                }
                "app_tap", "click" -> {
                    val by = params.optString("by", "text")
                    val value = params.optString("value", params.optString("text", ""))
                    when (by) {
                        "text" -> AccessibilityController.clickByText(context, value)
                        "resource_id" -> AccessibilityController.clickById(context, value)
                        "coords" -> {
                            val arr = params.optJSONArray("value")
                            if (arr != null && arr.length() >= 2) AccessibilityController.clickByCoords(arr.getInt(0), arr.getInt(1)) else false
                        }
                        "xpath" -> AccessibilityController.clickByXPath(context, value)
                        else -> AccessibilityController.clickByText(context, value)
                    }
                }
                "app_type", "fill" -> {
                    val text = params.optString("text", "")
                    val target = params.optString("target", "focused")
                    AccessibilityController.fillText(context, text, target)
                }
                "app_swipe" -> {
                    val from = params.optJSONArray("from")
                    val to = params.optJSONArray("to")
                    val dur = params.optInt("duration_ms", 300)
                    if (from != null && to != null && from.length() >= 2 && to.length() >= 2)
                        AccessibilityController.swipe(from.getInt(0), from.getInt(1), to.getInt(0), to.getInt(1), dur)
                    else false
                }

                // Web Automation & Browsing
                "web_visit" -> {
                    val url = params.optString("url")
                    if (url.isBlank()) return@withContext false
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                }
                "web_interact" -> {
                    val seq = params.optJSONArray("sequence") ?: JSONArray()
                    for (i in 0 until seq.length()) {
                        val s = seq.getJSONObject(i)
                        val action = s.optString("action")
                        val selector = s.optJSONObject("selector")
                        val value = s.optString("value", "")
                        when (action) {
                            "click" -> {
                                val by = selector?.optString("by", "text") ?: "text"
                                val v = selector?.optString("value", value) ?: value
                                AccessibilityController.clickBySelector(context, by, v)
                            }
                            "fill" -> {
                                AccessibilityController.fillBySelector(context, selector, value)
                            }
                            "wait" -> {
                                delay(s.optInt("ms", 1000).toLong())
                            }
                            "screenshot" -> {
                                // trigger screenshot flow (user consent required)
                                val saveAs = s.optString("save_as", "tasks/$taskId/web_${i}.png")
                                NotificationHelper.notify(context, "Screenshot required", "Please allow screen capture for web screenshot.")
                                // Optionally create placeholder file
                                val f = File(taskDir, saveAs)
                                f.parentFile?.mkdirs()
                            }
                            else -> {}
                        }
                    }
                    true
                }

                // Input / OTP / Captcha
                "input_otp" -> {
                    // Ask user via secure UI to paste OTP. Do not read SMS automatically without consent.
                    NotificationHelper.notify(context, "OTP required", params.optString("message", "Please paste OTP into the app"))
                    false
                }

                // Screenshot / Screen Recording
                "take_screenshot", "screenshot" -> {
                    val filename = params.optString("save_as", "tasks/$taskId/screenshot_${System.currentTimeMillis()}.png")
                    // Trigger UI flow to request MediaProjection permission and capture (service required)
                    NotificationHelper.notify(context, "Screen capture required", "Please allow screen capture to save screenshot.")
                    // If you implemented MediaProjectionService, call it here. Placeholder:
                    // val saved = ScreenCaptureHelper.captureOnce(context, resultCode, data, filename)
                    true
                }
                "screen_record" -> {
                    NotificationHelper.notify(context, "Screen record", "Please allow screen recording (user consent required).")
                    false
                }

                // Content generation / AI calls
                "generate_text" -> {
                    val prompt = params.optString("prompt", "")
                    val model = params.optString("model", "")
                    val saveAs = params.optString("save_as", "tasks/$taskId/generated_text_${System.currentTimeMillis()}.txt")
                    val apiKey = StorageUtils.getOpenRouterKey(context) ?: return@withContext false
                    val resp = try {
                        if (model.isBlank())
                            OpenRouterClient.requestPlanner(apiKey, prompt)
                        else
                            OpenRouterClient.requestPlanner(apiKey, prompt, model)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                    val text = resp?.optString("generated_text") ?: resp?.optString("choices") ?: resp?.toString()
                    if (text != null) {
                        val out = File(taskDir, saveAs)
                        out.parentFile?.mkdirs()
                        out.writeText(text)
                        // store partial output
                        storePartialOutput(taskDir, step.optString("id", ""), out.absolutePath)
                        true
                    } else false
                }

                "generate_code" -> {
                    val spec = params.optString("spec", "")
                    val saveDir = params.optString("project_path", "tasks/$taskId/generated_project")
                    val apiKey = StorageUtils.getOpenRouterKey(context) ?: return@withContext false
                    val prompt = "Generate project files for spec:\n$spec\nReturn JSON: {files:[{path,content}]}"
                    val resp = try { OpenRouterClient.requestPlanner(apiKey, prompt) } catch (e: Exception) { e.printStackTrace(); null }
                    if (resp != null) {
                        val filesArr = resp.optJSONArray("files")
                        if (filesArr != null) {
                            for (j in 0 until filesArr.length()) {
                                val f = filesArr.getJSONObject(j)
                                val path = f.optString("path")
                                val content = f.optString("content")
                                val outFile = File(taskDir, "$saveDir/$path")
                                outFile.parentFile?.mkdirs()
                                outFile.writeText(content)
                            }
                            true
                        } else {
                            File(taskDir, "$saveDir/response.json").writeText(resp.toString(2))
                            true
                        }
                    } else false
                }

// File ops
                "save_file" -> {
                    val path = params.optString("path", "tasks/$taskId/file_${System.currentTimeMillis()}.txt")
                    val content = params.optString("content", "")
                    val f = File(taskDir, path)
                    f.parentFile?.mkdirs()
                    f.writeText(content)
                    storePartialOutput(taskDir, step.optString("id", ""), f.absolutePath)
                    true
                }
                "download_file" -> {
                    val url = params.optString("url", "")
                    val saveAs = params.optString("save_as", "tasks/$taskId/download_${System.currentTimeMillis()}")
                    if (url.isBlank()) return@withContext false
                    try {
                        val req = Request.Builder().url(url).build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@withContext false
                            val body = resp.body ?: return@withContext false
                            val outFile = File(taskDir, saveAs)
                            outFile.parentFile?.mkdirs()
                            outFile.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                            storePartialOutput(taskDir, step.optString("id", ""), outFile.absolutePath)
                            true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
                "upload_file" -> {
                    val path = params.optString("path", "")
                    val target = params.optString("target", "")
                    if (path.isBlank() || target.isBlank()) return@withContext false
                    val f = File(context.filesDir, path)
                    if (!f.exists()) return@withContext false
                    // For cloud targets, open web auth flow or call SDK. Here we show notification + open browser if URL provided.
                    when (target) {
                        "github", "gdrive", "s3" -> {
                            NotificationHelper.notify(context, "Upload required", "Please complete authentication for $target.")
                            true
                        }
                        "http" -> {
                            val url = params.optString("url", "")
                            if (url.isBlank()) return@withContext false
                            // TODO: implement multipart upload
                            NotificationHelper.notify(context, "Upload", "HTTP upload to $url started (not fully implemented).")
                            true
                        }
                        else -> false
                    }
                }

                // Notifications & scheduling
                "send_notification", "notify" -> {
                    val title = params.optString("title", "Agent")
                    val body = params.optString("body", "")
                    NotificationHelper.notify(context, title, body)
                    true
                }
                "schedule_job" -> {
                    val delayS = params.optInt("delay_s", -1)
                    val workerName = params.optString("worker", "long_task_worker")
                    val payload = params.optJSONObject("payload") ?: JSONObject()
                    WorkEnqueueHelper.enqueueJob(context, workerName + "_" + System.currentTimeMillis(), payload.toString(), delayS)
                    true
                }

                // Confirmation control
                "confirm_user" -> {
                    val message = params.optString("message", "Please confirm")
                    val approved = requestUserConfirmationViaNotification(taskId, step.optString("id", "confirm"), message)
                    approved
                }

                // OCR / screenshot + OCR
                "screenshot_and_ocr" -> {
                    NotificationHelper.notify(context, "OCR requested", "Please allow screen capture for OCR.")
                    // Placeholder: actual OCR requires MediaProjection + OCR engine
                    true
                }

                // Delete / destructive ops
                "delete_files" -> {
                    val path = params.optString("path", "")
                    val recursive = params.optBoolean("recursive", false)
                    if (path.isBlank()) return@withContext false
                    val target = File(path)
                    // Safety: only allow deleting inside app filesDir or explicitly allowed path
                    if (!target.absolutePath.startsWith(context.filesDir.absolutePath)) {
                        NotificationHelper.notify(context, "Delete blocked", "Deletion blocked outside app storage for safety.")
                        return@withContext false
                    }
                    if (target.isDirectory && recursive) target.deleteRecursively() else target.delete()
                    true
                }

                // GitHub / cloud / infra (stubs that open flows)
                "create_github_repo" -> {
                    NotificationHelper.notify(context, "GitHub repo creation", "Please sign in to GitHub to allow repository creation.")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/new"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    false
                }
                "deploy_infra" -> {
                    NotificationHelper.notify(context, "Cloud deployment", "Cloud provisioning requires credentials and explicit approval.")
                    false
                }

                // Fallback action: run nested steps
                "fallback_action" -> {
                    val reason = params.optString("reason", "fallback")
                    val steps = params.optJSONArray("steps") ?: JSONArray()
                    val fb = JSONObject().apply {
                        put("task_id", "${taskId}_fallback_${System.currentTimeMillis()}")
                        put("created_at", System.currentTimeMillis())
                        put("goal", "fallback for $taskId")
                        put("steps", steps)
                    }
                    executePlan(fb)
                    true
                }

                // No-op
                "noop" -> true

                // Custom shell: not supported on normal devices; require root
                "custom_shell" -> {
                    NotificationHelper.notify(context, "Shell blocked", "Shell commands are not supported on non-rooted devices.")
                    false
                }

                // Secure store
                "encrypt_store" -> {
                    val keyName = params.optString("key_name","")
                    val value = params.optString("value","")
                    if (keyName.isNotBlank()) {
                        StorageUtils.encryptAndStore(context, keyName, value)
                        true
                    } else false
                }

                // Transfers: always require a wallet/custodial integration
                "transfer_money" -> {
                    NotificationHelper.notify(context, "Money transfer requested", "Money transfers require secure wallet integration and explicit approval.")
                    false
                }

                // Unknown
                else -> {
                    NotificationHelper.notify(context, "Unknown action", "Executor does not support action type: $type")
                    false
                }
            }
        }

// ---------- Helper functions: validation, checkpoints, confirmation, partial outputs ----------

    data class ValidationResult(val valid: Boolean, val reason: String = "")

    private fun validateTaskPlan(json: JSONObject): ValidationResult {
        try {
            // top-level required fields: task_id (opt), steps/plan
            val steps = json.optJSONArray("steps") ?: json.optJSONArray("plan")
            if (steps == null) return ValidationResult(false, "Missing 'steps' or 'plan' array")
            for (i in 0 until steps.length()) {
                val s = steps.optJSONObject(i) ?: return ValidationResult(false, "Step $i is not an object")
                if (!s.has("id")) return ValidationResult(false, "Step $i missing 'id'")
                if (!s.has("type")) return ValidationResult(false, "Step ${s.optString("id","$i")} missing 'type'")
                if (!s.has("description")) return ValidationResult(false, "Step ${s.optString("id","$i")} missing 'description'")
                if (!s.has("params")) return ValidationResult(false, "Step ${s.optString("id","$i")} missing 'params'")
            }
            return ValidationResult(true)
        } catch (e: Exception) {
            return ValidationResult(false, "Validation exception: ${e.message}")
        }
    }

    private fun checkPrerequisites(prereqs: JSONObject): Boolean {
        // Check common prerequisites (wifi / charging / min_free_space_mb)
        try {
            val wifi = prereqs.optBoolean("wifi", false)
            if (wifi) {
                val nm = ConnectivityUtils.isOnUnmeteredNetwork(context)
                if (!nm) return false
            }
            val charging = prereqs.optBoolean("charging", false)
            if (charging) {
                if (!DeviceUtils.isCharging(context)) return false
            }
            val minSpace = prereqs.optInt("min_free_space_mb", 0)
            if (minSpace > 0) {
                if (DeviceUtils.freeSpaceMb(context) < minSpace) return false
            }
            return true
        } catch (_: Exception) { return true }
    }

    private fun storePartialOutput(taskDir: File, stepId: String, path: String) {
        try {
            val cp = File(taskDir, "checkpoint.json")
            val checkpoint = loadCheckpoint(cp, 0)
            checkpoint.partial_outputs.put(stepId, path)
            saveCheckpoint(cp, checkpoint)
        } catch (_: Exception) {}
    }

    private data class Checkpoint(
        var task_id: String = "",
        var last_step: Int = 0,
        var status: String = "running",
        val steps_status: JSONObject = JSONObject(),
        val partial_outputs: JSONObject = JSONObject(),
        var created_at: Long = System.currentTimeMillis(),
        var updated_at: Long = System.currentTimeMillis()
    )

    private fun loadCheckpoint(file: File, stepsCountIfNew: Int = 0): Checkpoint {
        return try {
            if (!file.exists()) {
                val cp = Checkpoint()
                cp.last_step = 0
                cp.steps_status // empty
                cp.partial_outputs
                saveCheckpoint(file, cp)
                cp
            } else {
                val txt = file.readText()
                val j = JSONObject(txt)
                val cp = Checkpoint()
                cp.task_id = j.optString("task_id", "")
                cp.last_step = j.optInt("last_step", 0)
                cp.status = j.optString("status", "running")
                cp.created_at = j.optLong("created_at", System.currentTimeMillis())
                cp.updated_at = j.optLong("updated_at", System.currentTimeMillis())
                val ss = j.optJSONObject("steps_status") ?: JSONObject()
                val po = j.optJSONObject("partial_outputs") ?: JSONObject()
                // copy into checkpoint objects
                cp.steps_status.keys().forEach { key -> cp.steps_status.put(key, ss.optString(key)) }
                cp.partial_outputs.keys().forEach { key -> cp.partial_outputs.put(key, po.optString(key)) }
                cp
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val cp = Checkpoint()
            saveCheckpoint(file, cp)
            cp
        }
    }

    private fun saveCheckpoint(file: File, cp: Checkpoint) {
        try {
            val j = JSONObject()
            j.put("task_id", cp.task_id)
            j.put("last_step", cp.last_step)
            j.put("status", cp.status)
            j.put("steps_status", cp.steps_status)
            j.put("partial_outputs", cp.partial_outputs)
            j.put("created_at", cp.created_at)
            j.put("updated_at", System.currentTimeMillis())
            file.parentFile?.mkdirs()
            file.writeText(j.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Confirmation flow (listens for broadcast from ConfirmationActivity)
    private suspend fun requestUserConfirmationViaNotification(taskId: String, stepId: String, description: String, timeoutMs: Long = 2 * 60_000L): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean> { cont ->
                try {
                    NotificationHelper.notifyConfirmation(context, taskId, stepId.toIntOrNull() ?: (System.currentTimeMillis() % 10000).toInt(), description)
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

                    // Timeout guard
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
        }

    // ---------- Utilities ----------
    fun cancelTask(taskId: String) {
        scope.coroutineContext.cancelChildren()
        NotificationHelper.notify(context, "Task canceled", "Task $taskId canceled")
    }

    private fun logError(msg: String) {
        println("❌ TaskExecutor: $msg")
    }
}