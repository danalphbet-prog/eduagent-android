package com.example.llama

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val messages = mutableListOf<Message>()
    private val adapter = MessageAdapter(messages, ::showMessageDetails)
    private lateinit var modelRepository: ModelRepository
    private lateinit var repository: ChatRepository
    private lateinit var engine: InferenceEngine

    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var send: MaterialButton
    private lateinit var status: TextView
    private lateinit var downloadPanel: View
    private lateinit var downloadDescription: TextView
    private lateinit var downloadProgress: ProgressBar
    private lateinit var downloadButton: MaterialButton
    private lateinit var emptyState: View
    private var generationJob: Job? = null
    private var isReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.chat_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        modelRepository = ModelRepository(applicationContext)
        repository = LocalChatRepository(applicationContext)
        list = findViewById(R.id.messages)
        input = findViewById(R.id.user_input)
        send = findViewById(R.id.send_button)
        status = findViewById(R.id.engine_status)
        emptyState = findViewById(R.id.empty_state)
        downloadPanel = findViewById(R.id.download_panel)
        downloadDescription = findViewById(R.id.download_description)
        downloadProgress = findViewById(R.id.download_progress)
        downloadButton = findViewById(R.id.download_button)

        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        list.itemAnimator = null // streaming should never make message bubbles jump
        refreshComposer()

        send.setOnClickListener { sendMessage() }
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                sendMessage()
                true
            } else false
        }
        downloadButton.setOnClickListener { beginDownload() }
        findViewById<View>(R.id.model_info).setOnClickListener { showModelInfo() }

        lifecycleScope.launch {
            val history = repository.load()
            messages.clear()
            messages.addAll(history)
            adapter.notifyDataSetChanged()
            updateEmptyState()
            if (messages.isNotEmpty()) list.scrollToPosition(messages.lastIndex)

            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                // The native library initializes asynchronously on its own worker.
                withTimeout(30_000) {
                    val state = engine.state.first {
                        it is InferenceEngine.State.Initialized ||
                            it is InferenceEngine.State.Error
                    }
                    if (state is InferenceEngine.State.Error) throw state.exception
                }
                val model = modelRepository.verifiedModelOrNull()
                if (model != null) {
                    loadModel(model)
                } else {
                    showDownloadPanel("Qwen2.5 1.5B • download único de aproximadamente 1,12 GB.")
                    if (!getPreferences(MODE_PRIVATE).getBoolean("download_prompted", false)) {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("download_prompted", true).apply()
                        askFirstDownload()
                    }
                }
            } catch (e: Exception) {
                showDownloadPanel("Falha ao preparar a IA: ${e.message ?: "erro desconhecido"}")
            }
        }
    }

    private fun askFirstDownload() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Instalar IA local?")
            .setMessage("O EduAgent precisa baixar o Qwen2.5 1.5B (1,12 GB) uma vez. " +
                "O modelo fica salvo neste aplicativo. Recomendamos Wi-Fi e pelo menos 1,5 GB livres.")
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Baixar") { _, _ -> beginDownload() }
            .show()
    }

    private fun beginDownload() {
        if (downloadButton.isEnabled.not()) return
        downloadPanel.visibility = View.VISIBLE
        downloadButton.isEnabled = false
        downloadProgress.visibility = View.VISIBLE
        status.text = "Baixando modelo..."
        lifecycleScope.launch {
            try {
                val file = modelRepository.download { current, total ->
                    runOnUiThread {
                        if (total > 0) {
                            downloadProgress.isIndeterminate = false
                            downloadProgress.progress = (current * 100L / total).coerceIn(0, 100).toInt()
                        } else downloadProgress.isIndeterminate = true
                        downloadDescription.text =
                            "Baixado: ${formatMb(current)} de ${formatMb(total)} · pode retomar se interromper"
                        if (current >= total && total > 0) status.text = "Verificando integridade..."
                    }
                }
                loadModel(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showDownloadPanel("${e.message ?: "Falha no download"} Toque para tentar novamente.")
            } finally {
                downloadButton.isEnabled = true
                downloadProgress.visibility = View.GONE
            }
        }
    }

    private suspend fun loadModel(file: File) {
        status.text = "Carregando Qwen na memória..."
        downloadPanel.visibility = View.GONE
        isReady = false
        refreshComposer()
        // The native engine is a process-wide singleton. Reuse a warm model.
        if (!engine.state.value.isModelLoaded) {
            engine.loadModel(file.absolutePath)
            engine.setSystemPrompt(buildSystemPrompt())
        }
        isReady = true
        status.text = "Qwen2.5 · IA local pronta"
        refreshComposer()
    }

    private fun buildSystemPrompt(): String {
        val previous = messages.filter { it.completed && it.content.isNotBlank() }
            .takeLast(8)
            .joinToString("\n") { (if (it.isUser) "Pessoa" else "Tutor") + ": " + it.content.take(500) }
        return """
            Você é EduAgent, um tutor experimental de português brasileiro. Responda claramente,
            em frases curtas e amistosas. Em Matemática, ensine passo a passo e dê pistas antes de
            revelar uma resposta. Nunca invente que viu informações de outras aplicações.
            Você é uma IA e pode errar. Em situações delicadas ou de risco, recomende conversar
            com um adulto responsável. Não peça dados pessoais da criança.
            ${if (previous.isBlank()) "" else "Contexto recente salvo no aparelho:\n$previous"}
        """.trimIndent()
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || !isReady || generationJob?.isActive == true) return
        input.text.clear()
        val user = Message(UUID.randomUUID().toString(), text, isUser = true)
        val assistant = Message(UUID.randomUUID().toString(), "", isUser = false, completed = false)
        val startIndex = messages.size
        messages.add(user)
        messages.add(assistant)
        adapter.notifyItemRangeInserted(startIndex, 2)
        updateEmptyState()
        list.scrollToPosition(messages.lastIndex)
        refreshComposer(generating = true)
        persistMessages()

        generationJob = lifecycleScope.launch {
            val started = SystemClock.elapsedRealtime()
            var firstToken = 0L
            val output = StringBuilder()
            var failure: String? = null
            try {
                engine.sendUserPrompt(text, predictLength = 448).collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        if (firstToken == 0L) firstToken = SystemClock.elapsedRealtime() - started
                        output.append(chunk)
                        assistant.content = output.toString()
                        updateMessage(assistant)
                    }
                }
            } catch (e: CancellationException) {
                failure = "Resposta interrompida"
            } catch (e: Exception) {
                failure = e.message ?: "Erro durante a geração"
            } finally {
                val total = SystemClock.elapsedRealtime() - started
                try {
                    val native = engine.lastGenerationMetrics()
                    assistant.metrics = MessageMetrics(
                        promptTokens = native.promptTokens,
                        outputTokens = native.generatedTokens,
                        prefillMs = native.prefillMs,
                        firstTokenMs = firstToken,
                        totalMs = total,
                    )
                } catch (_: Exception) { /* still show the elapsed wall time */
                    assistant.metrics = MessageMetrics(0, 0, 0, firstToken, total)
                }
                if (failure != null) assistant.content =
                    if (output.isEmpty()) "⚠ $failure" else "${output}\n\n⚠ $failure"
                assistant.completed = true
                updateMessage(assistant)
                persistMessages()
                refreshComposer()
            }
        }
    }

    private fun updateMessage(msg: Message) {
        val position = messages.indexOfFirst { it.id == msg.id }
        if (position >= 0) {
            adapter.notifyItemChanged(position)
            if (!list.canScrollVertically(1)) list.scrollToPosition(position)
        }
    }

    private fun persistMessages() {
        val snapshot = messages.map { it.copy() }
        lifecycleScope.launch { runCatching { repository.save(snapshot) } }
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshComposer(generating: Boolean = false) {
        val enabled = isReady && !generating
        input.isEnabled = enabled
        send.isEnabled = enabled
        input.hint = if (isReady) "Mensagem" else "Aguardando instalação da IA..."
    }

    private fun showDownloadPanel(description: String) {
        status.text = "IA aguardando instalação"
        downloadPanel.visibility = View.VISIBLE
        downloadDescription.text = description
        downloadButton.text = "Baixar / retomar"
        isReady = false
        refreshComposer()
    }

    private fun showMessageDetails(message: Message) {
        val timestamp = DateFormat.getDateTimeInstance().format(Date(message.createdAt))
        val data = buildString {
            appendLine("Tipo: ${if (message.isUser) "Mensagem enviada" else "Resposta da IA"}")
            appendLine("Data: $timestamp")
            appendLine("ID: ${message.id}")
            if (!message.isUser) {
                appendLine("Modelo: ${ModelRepository.MODEL_NAME}")
                appendLine("Motor: llama.cpp (Android nativo)")
                message.metrics?.let { m ->
                    appendLine("\nTempo total: ${formatSeconds(m.totalMs)}")
                    appendLine("Até 1º texto: ${formatSeconds(m.firstTokenMs)}")
                    appendLine("Prefill nativo: ${m.prefillMs} ms")
                    appendLine("Tokens do prompt: ${m.promptTokens}")
                    appendLine("Tokens gerados: ${m.outputTokens}")
                    appendLine("Velocidade estimada: ${"%.1f".format(Locale.US, m.tokensPerSecond)} tokens/s")
                    appendLine("\nContadores vêm do motor nativo; nenhum desses números é gerado pela IA.")
                }
            } else appendLine("Caracteres: ${message.content.length}")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Informações da mensagem")
            .setMessage(data)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showModelInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle("EduAgent · informações")
            .setMessage("Modelo: ${ModelRepository.MODEL_NAME}\n" +
                "Contexto ativo: 4.096 tokens\n" +
                "Respostas: até 448 tokens\n" +
                "Execução: local, pelo llama.cpp\n" +
                "Mensagens: salvas apenas no aparelho\n" +
                "Firebase: reservado para uma versão futura\n\n" +
                "Segure uma bolha de mensagem para conferir latência e tokens.")
            .setPositiveButton("Entendi", null)
            .show()
    }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    companion object {
        private fun formatMb(bytes: Long): String =
            if (bytes < 0) "? MB" else "${bytes / 1_000_000} MB"
    }
}
