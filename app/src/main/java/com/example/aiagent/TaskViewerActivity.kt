package com.example.aiagent

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File

class TaskViewerActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private lateinit var refreshBtn: Button
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val adapter by lazy { TaskListAdapter(this, mutableListOf()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_viewer)
        listView = findViewById(R.id.taskListView)
        refreshBtn = findViewById(R.id.btnRefreshTasks)
        listView.adapter = adapter

        refreshBtn.setOnClickListener { loadTasks() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val t = adapter.items[position]
            showTaskOptions(t)
        }

        loadTasks()
    }

    private fun loadTasks() {
        scope.launch {
            val tasks = withContext(Dispatchers.IO) { TaskRepository.listTasks(this@TaskViewerActivity) }
            adapter.setItems(tasks)
        }
    }

    private fun showTaskOptions(task: TaskRepository.TaskItem) {
        val items = arrayOf("View checkpoint/log", "Pause task", "Resume task", "Abort task", "Open folder")
        AlertDialog.Builder(this).setTitle(task.id)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showFile(task.checkpointFile)
                    1 -> TaskRepository.pauseTask(this, task)
                    2 -> TaskRepository.resumeTask(this, task)
                    3 -> TaskRepository.abortTask(this, task)
                    4 -> openFolder(task.dir)
                }
            }.show()
    }

    private fun showFile(f: File) {
        val txt = if (f.exists()) f.readText() else "No file"
        AlertDialog.Builder(this).setTitle(f.name).setMessage(txt).setPositiveButton("OK", null).show()
    }

    private fun openFolder(dir: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        // Can't reliably open file manager across devices; show location instead
        AlertDialog.Builder(this).setTitle("Folder location").setMessage(dir.absolutePath).setPositiveButton("OK", null).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}