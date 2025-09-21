package com.example.aiagent

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<Pair<Boolean, String>>() // true=user, false=ai

    fun addUserMessage(text: String) {
        items.add(Pair(true, text))
        notifyItemInserted(items.size - 1)
    }

    fun addAIMessage(text: String) {
        items.add(Pair(false, text))
        notifyItemInserted(items.size - 1)
    }

    fun updateLastAIMessage(text: String) {
        for (i in items.size - 1 downTo 0) {
            if (!items[i].first) {
                items[i] = Pair(false, text)
                notifyItemChanged(i)
                return
            }
        }
        addAIMessage(text)
    }

    override fun getItemViewType(position: Int): Int = if (items[position].first) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            val v = inflater.inflate(R.layout.item_chat_user, parent, false) as TextView
            object : RecyclerView.ViewHolder(v) {}
        } else {
            val v = inflater.inflate(R.layout.item_chat_ai, parent, false) as TextView
            object : RecyclerView.ViewHolder(v) {}
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val tv = holder.itemView as TextView
        tv.text = items[position].second
    }

    override fun getItemCount(): Int = items.size
}