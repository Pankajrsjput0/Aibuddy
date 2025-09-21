package com.example.aiagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

/**
 * Opens to request user confirmation for a particular step.
 * Expects extras:
 *  - "message" : String
 * Returns RESULT_OK if confirmed, RESULT_CANCELED if not.
 */
class ConfirmationActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = intent.getStringExtra("message") ?: "Confirm action?"
        // For UX simplicity: run biometric prompt
        scope.launch {
            val ok = BiometricUtils.requestConfirmation(this@ConfirmationActivity, message)
            if (ok) {
                setResult(Activity.RESULT_OK)
            } else setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}