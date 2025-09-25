package com.example.aiagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

/**
 * Opens to request user confirmation for a particular step.
 * Expects extras:
 *  - "message" : String (required message to show)
 *  - "taskId" : String (optional, for broadcast mode)
 *  - "stepId" : Int (optional, for broadcast mode)
 * 
 * Behavior:
 * - If taskId is provided, sends broadcast with confirmation result
 * - Always returns RESULT_OK if confirmed, RESULT_CANCELED if not
 */
class ConfirmationActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Extract intent extras
        val message = intent.getStringExtra("message") 
            ?: intent.getStringExtra(ConfirmationConstants.EXTRA_MESSAGE) 
            ?: "Confirm action?"
        
        val taskId = intent.getStringExtra(ConfirmationConstants.EXTRA_TASK_ID) ?: ""
        val stepId = intent.getIntExtra(ConfirmationConstants.EXTRA_STEP_ID, -1)
        
        // For UX simplicity: run biometric prompt
        scope.launch {
            val ok = BiometricUtils.requestConfirmation(this@ConfirmationActivity, message)
            
            // Send broadcast if taskId is provided
            if (taskId.isNotEmpty()) {
                sendResultBroadcast(taskId, stepId, ok)
            }
            
            // Set activity result
            if (ok) {
                setResult(Activity.RESULT_OK)
            } else {
                setResult(Activity.RESULT_CANCELED)
            }
            
            finish()
        }
    }

    private fun sendResultBroadcast(taskId: String, stepId: Int, ok: Boolean) {
        val action = ConfirmationConstants.ACTION_CONFIRM_PREFIX + taskId
        val broadcastIntent = Intent(action).apply {
            putExtra(ConfirmationConstants.EXTRA_TASK_ID, taskId)
            putExtra(ConfirmationConstants.EXTRA_STEP_ID, stepId)
            putExtra(ConfirmationConstants.EXTRA_RESULT, ok)
        }
        sendBroadcast(broadcastIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}