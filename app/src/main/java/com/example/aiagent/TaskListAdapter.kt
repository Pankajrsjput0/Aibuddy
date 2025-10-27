package com.example.aiagent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class TaskListAdapter(
    private val context: Context,
    var items: MutableList<TaskRepository.TaskItem>
) : BaseAdapter() {

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(
            android.R.layout.simple_list_item_2,
            parent,
            false
        )

        val text1 = view.findViewById<TextView>(android.R.id.text1)
        val text2 = view.findViewById<TextView>(android.R.id.text2)

        val task = items[position]
        text1.text = task.id
        text2.text = "Status: ${task.status}"

        return view
    }

    fun setItems(newItems: List<TaskRepository.TaskItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
