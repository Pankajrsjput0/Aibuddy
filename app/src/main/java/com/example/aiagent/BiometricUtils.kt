package com.example.aiagent

import android.app.Activity
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object BiometricUtils {
    suspend fun requestConfirmation(context: Context, message: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val activity = context as? Activity ?: run {
                cont.resume(false); return@suspendCancellableCoroutine
            }
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(activity, executor, object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (cont.isActive) cont.resume(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(false)
                }
                override fun onAuthenticationFailed() {
                    // do nothing
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm action")
                .setSubtitle(message)
                .setNegativeButtonText("Cancel")
                .build()
            prompt.authenticate(info)
        }
}