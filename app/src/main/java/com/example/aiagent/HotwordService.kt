package com.example.aiagent

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

/**
 * Skeleton: integrate your chosen hotword engine inside startHotwordLoop().
 * When hotword detected -> call onHotwordDetected().
 */
class HotwordService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceForHotword()
        running = true
        scope.launch { startHotwordLoop() }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundServiceForHotword() {
        NotificationHelper.ensureChannel(this)
        val n = NotificationHelper.createSimpleNotification(this, "AI Agent", "Hotword listening")
        startForeground(201, n)
    }

    private suspend fun startHotwordLoop() {
        // TODO: integrate actual SDK here (Porcupine / Vosk / Picovoice)
        // Pseudocode:
        // val engine = HotwordEngine.create(...).initialize(...)
        // engine.startListening { onHotwordDetected() }
        while (running) {
            // placeholder: check if real engine detected keyword
            delay(1000)
        }
    }

    private fun onHotwordDetected() {
        // Open chat UI or trigger SpeechRecognizer
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("hotword", true)
        }
        startActivity(i)
    }
}