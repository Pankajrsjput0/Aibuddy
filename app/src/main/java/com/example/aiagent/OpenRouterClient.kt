package com.example.aiagent

import android.content.Context
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException

object OpenRouterClient {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient

    init {
        val logging = HttpLoggingInterceptor()
        logging.level = HttpLoggingInterceptor.Level.BASIC
        client = OkHttpClient.Builder().addInterceptor(logging).build()
    }

    // Load system prompt from assets
    private fun loadSystemPrompt(context: Context): String {
        return context.assets.open("system_prompt.txt")
            .bufferedReader().use { it.readText() }
    }

    suspend fun sendMessage(
        context: Context,
        apiKey: String,
        userMessage: String,
        model: String = "oai/gpt-4o-mini"
    ): JSONObject =
        suspendCancellableCoroutine { cont ->
            val url = "https://openrouter.ai/api/v1/chat/completions"

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", loadSystemPrompt(context))
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            }

            val bodyJson = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 800)
                put("stream", false)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, bodyJson))
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (cont.isActive) cont.resumeWithException(
                                IOException("Unexpected code ${it.code}")
                            )
                            return
                        }
                        val txt = it.body!!.string()
                        try {
                            val json = JSONObject(txt)
                            if (cont.isActive) cont.resume(json)
                        } catch (ex: Exception) {
                            if (cont.isActive) cont.resumeWithException(ex)
                        }
                    }
                }
            })
        }
}