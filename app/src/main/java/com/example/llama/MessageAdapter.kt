package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MessageMetrics(
    val promptTokens: Long,
    val outputTokens: Long,
    val prefillMs: Long,
    val firstTokenMs: Long,
    val totalMs: Long,
) {
    val tokensPerSecond: Double
        get() = if (totalMs <= prefillMs) 0.0
        else outputTokens * 1000.0 / (totalMs - prefillMs)
}

data class Message(
    val id: String,
    var content: String,
    val isUser: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    var metrics: MessageMetrics? = null,
    var completed: Boolean = true,
    var attachmentPath: String? = null,
    var modelName: String? = null,
)

class MessageAdapter(
    private val messages: List<Message>,
    private val onLongPress: (Message) -> Unit,
) : RecyclerView.Adapter<MessageAdapter.MessageHolder>() {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = messages[position].id.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = if (messages[position].isUser) 1 else 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        val layout = if (viewType == 1) R.layout.item_message_user else R.layout.item_message_assistant
        return MessageHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        val item = messages[position]
        holder.content.text = if (item.content.isEmpty() && !item.completed) "Pensando…" else item.content
        holder.content.visibility = if (item.content.isBlank() && item.attachmentPath != null) View.GONE else View.VISIBLE
        holder.image?.let { preview ->
            val bitmap = ImageAttachmentStore.thumbnail(item.attachmentPath)
            preview.visibility = if (bitmap == null) View.GONE else View.VISIBLE
            preview.setImageBitmap(bitmap)
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.createdAt))
        holder.metadata.text = when {
            item.isUser -> time
            !item.completed -> "$time · gerando…"
            item.modelName == VisionModelRepository.NAME -> "$time · visão · ${item.metrics?.let { formatSeconds(it.totalMs) } ?: "concluído"}"
            item.metrics != null -> "$time · ${formatSeconds(item.metrics!!.totalMs)} · segure para detalhes"
            else -> time
        }
        holder.itemView.setOnLongClickListener { onLongPress(item); true }
        holder.itemView.setOnClickListener(null)
    }

    override fun getItemCount(): Int = messages.size

    class MessageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: TextView = view.findViewById(R.id.msg_content)
        val metadata: TextView = view.findViewById(R.id.msg_meta)
        val image: ImageView? = view.findViewById(R.id.msg_image)
    }
}

fun formatSeconds(ms: Long): String = String.format(Locale.getDefault(), "%.2fs", ms / 1000.0)
