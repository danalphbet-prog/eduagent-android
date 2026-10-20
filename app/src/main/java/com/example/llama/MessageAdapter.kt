package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
    private val onCodeAction: (Message, CodeBlock, CodeAction) -> Unit,
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
        val blocks = if (!item.isUser && item.completed) CodeBlocks.parse(item.content) else emptyList()
        holder.content.text = when {
            item.content.isEmpty() && !item.completed -> "Pensando…"
            blocks.isNotEmpty() -> CodeBlocks.prose(item.content).ifBlank { "Código gerado:" }
            else -> item.content
        }
        holder.content.visibility = if (item.content.isBlank() && item.attachmentPath != null) View.GONE else View.VISIBLE
        holder.codeBlocks?.let { container ->
            container.removeAllViews()
            container.visibility = if (blocks.isEmpty()) View.GONE else View.VISIBLE
            blocks.forEach { block -> addCodePanel(container, item, block) }
        }
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

    private fun addCodePanel(container: LinearLayout, message: Message, block: CodeBlock) {
        val ctx = container.context
        val dp = ctx.resources.displayMetrics.density
        fun d(value: Int) = (value * dp).toInt()
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(12), d(9), d(12), d(7))
            background = ctx.getDrawable(R.drawable.bg_code_panel)
        }
        container.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(8) })
        panel.addView(TextView(ctx).apply {
            text = if (block.isSetData) "setData · ação aguardando aprovação" else block.language.uppercase(Locale.ROOT)
            textSize = 11f
            setTextColor(0xFF9CADDE.toInt())
        })
        panel.addView(TextView(ctx).apply {
            text = block.source
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            maxLines = 14
            setPadding(0, d(8), 0, d(8))
            setTextColor(0xFFF4F6FF.toInt())
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val horizontal = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        panel.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
        fun button(label: String, iconId: Int, action: CodeAction) {
            row.addView(MaterialButton(ctx).apply {
                text = label
                textSize = 11f
                icon = ctx.getDrawable(iconId)
                iconTint = android.content.res.ColorStateList.valueOf(0xFF496EEB.toInt())
                iconSize = d(16)
                insetTop = 0
                insetBottom = 0
                minHeight = d(36)
                setOnClickListener { onCodeAction(message, block, action) }
            }, LinearLayout.LayoutParams(-2, d(40)).apply { marginEnd = d(5) })
        }
        button("Copiar", R.drawable.ic_lucide_copy, CodeAction.COPY)
        button("Abrir", R.drawable.ic_lucide_external_link, CodeAction.OPEN)
        button("Download", R.drawable.ic_lucide_download, CodeAction.DOWNLOAD)
        button(if (block.isSetData) "Aplicar setData" else "Salvar arquivo",
            R.drawable.ic_lucide_folder_open, CodeAction.SAVE)
        if (block.isShell) button("Executar", R.drawable.ic_lucide_terminal, CodeAction.RUN)
    }

    class MessageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: TextView = view.findViewById(R.id.msg_content)
        val metadata: TextView = view.findViewById(R.id.msg_meta)
        val image: ImageView? = view.findViewById(R.id.msg_image)
        val codeBlocks: LinearLayout? = view.findViewById(R.id.msg_code_blocks)
    }
}

fun formatSeconds(ms: Long): String = String.format(Locale.getDefault(), "%.2fs", ms / 1000.0)
