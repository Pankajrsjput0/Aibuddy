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

    private fun buildPlannerPrompt(goal: String): String {
        return """
SYSTEM: You are an autonomous assistant that outputs ONLY valid JSON following this schema:
{ "task_id":"<id>", "goal":"...", "plan":[{ "step_id":1, "type":"open_app|web_visit|click|fill|generate_text|save_file|screenshot|wait|confirm_user", "description":"", "params":{}, "requires_confirmation":false }], "fallback":[], "metadata":{} }

USER GOAL: "$goal"

CONSTRAINTS:
- This device has Accessibility, Web automation via Accessibility, TTS, File IO.
- For any high-risk step (payment, account creation) set requires_confirmation=true and provide exact human prompt for confirmation.
- Output valid JSON only.
"""
    }
}