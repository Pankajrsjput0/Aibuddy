package com.example.aiagent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.*
import org.json.JSONObject
import android.widget.Button
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    private lateinit var chatRecycler: RecyclerView
    private lateinit var inputText: EditText
    private lateinit var sendButton: Button
    private lateinit var adapter: ChatAdapter
    private lateinit var executor: TaskExecutor
    private lateinit var aiBrain: AIBrain
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatRecycler = findViewById(R.id.chatRecycler)
        inputText = findViewById(R.id.inputText)
        sendButton = findViewById(R.id.sendButton)

        adapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this)
        chatRecycler.adapter = adapter

        aiBrain = AIBrain(this)
        executor = TaskExecutor(this)

        sendButton.setOnClickListener {
            val text = inputText.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            inputText.setText("")
            adapter.addUserMessage(text)
            adapter.addAIMessage("Thinking...")
            scope.launch {
                try {
                    val planJson = aiBrain.requestPlan(text)
                    adapter.updateLastAIMessage("Executing plan...")
                    executor.executePlan(planJson)
                    adapter.addAIMessage("Task queued. Check Tasks or Logs.")
                } catch (e: Exception) {
                    adapter.updateLastAIMessage("Error: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}