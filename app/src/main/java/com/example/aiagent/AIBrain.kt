package com.example.aiagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AIBrain(private val context: Context) {

    suspend fun requestPlan(userGoal: String): JSONObject = withContext(Dispatchers.IO) {
        val apiKey = StorageUtils.getOpenRouterKey(context)
            ?: throw Exception("OpenRouter API key not set. Set in Settings.")
        val prompt = buildPlannerPrompt(userGoal)
        OpenRouterClient.requestPlanner(apiKey, prompt)
    }

    // ✅ This reads system_prompt.txt from assets folder
    private fun loadSystemPrompt(): String {
        return try {
            context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "You are a helpful AI assistant." // fallback if file not found
        }
    }

    // ✅ Now it includes the system prompt + user goal
    private fun buildPlannerPrompt(goal: String): String {
        val systemPrompt = loadSystemPrompt()

        return """
SYSTEM PROMPT:
$systemPrompt

USER GOAL:
"$goal"

CONSTRAINTS:
- This device has Accessibility, Web automation via Accessibility, TTS, File IO.
- For any high-risk step (payment, account creation) set requires_confirmation=true and provide exact human prompt for confirmation.
- Output valid JSON only.
"""
    }
}