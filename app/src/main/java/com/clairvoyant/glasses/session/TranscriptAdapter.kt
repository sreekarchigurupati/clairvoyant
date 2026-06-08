package com.clairvoyant.glasses.session

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clairvoyant.glasses.R

/**
 * Renders a session's transcript: assistant text blocks and tool-use lines. Tiny by design —
 * the model ([SessionData.transcript]) is authoritative; the Activity calls [submit] on change.
 */
class TranscriptAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<TranscriptItem> = emptyList()

    fun submit(newItems: List<TranscriptItem>) {
        items = newItems.toList()
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is TranscriptItem.Assistant -> TYPE_ASSISTANT
        is TranscriptItem.Tool -> TYPE_TOOL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ASSISTANT) {
            AssistantVH(inflater.inflate(R.layout.item_transcript_assistant, parent, false) as TextView)
        } else {
            ToolVH(inflater.inflate(R.layout.item_transcript_tool, parent, false) as TextView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TranscriptItem.Assistant -> (holder as AssistantVH).text.text = item.text
            is TranscriptItem.Tool -> (holder as ToolVH).text.text = "⚙ ${item.name} · ${item.summary}"
        }
    }

    private class AssistantVH(val text: TextView) : RecyclerView.ViewHolder(text)
    private class ToolVH(val text: TextView) : RecyclerView.ViewHolder(text)

    private companion object { const val TYPE_ASSISTANT = 0; const val TYPE_TOOL = 1 }
}
