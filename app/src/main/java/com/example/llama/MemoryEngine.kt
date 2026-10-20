package com.example.llama

/** Memória pequena, explícita e independente do contexto volátil da inferência nativa. */
enum class MemoryProfile { LIGHT, BALANCED, DEEP }

data class ConversationMemory(
    val activeInstructions: List<String> = emptyList(),
    val checklistActive: Boolean = false,
    val checklistItems: List<String> = emptyList(),
    val checklistMarker: String = "✅",
    val lastChecklistAction: String = "",
    val summary: String = "",
    val summarizedThroughId: String? = null,
)

data class MemoryTransition(val state: ConversationMemory, val directReply: String? = null)

/**
 * Tarefas e preferências explícitas são interpretadas de modo determinístico.
 * Isso evita gastar tokens para lembrar se "Janela." é um item da checklist.
 * Perguntas abertas continuam sendo respondidas normalmente pelo Qwen.
 */
object MemoryEngine {
    private val checklistRequest = Regex("check[ -]?list|lista de tarefas|lista de afazeres", RegexOption.IGNORE_CASE)
    private val instructionRequest = Regex(
        "^(a partir de agora|daqui pra frente|sempre que|lembre que|não esqueça que|responda sempre|quero que você|quando eu disser|mantenha a instrução)",
        RegexOption.IGNORE_CASE,
    )
    private val checklistCommands = Regex(
        "^(adicione|coloque|mude|troque|altere|substitua|remova|retire|apague|mostre|liste|exiba|termine|finalize|encerre|pare|crie|faça|gere|o emoji|emoji|qual|como|por que|porque|obrigad|ok[,!.]?$)",
        RegexOption.IGNORE_CASE,
    )

    fun onUserInput(state: ConversationMemory, input: String, settings: AiSettings): MemoryTransition {
        val text = input.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return MemoryTransition(state)
        var memory = state

        if (settings.keepActiveInstructions && instructionRequest.containsMatchIn(text)) {
            val limit = if (settings.memoryProfile == MemoryProfile.DEEP) 12 else 5
            val rule = text.take(300)
            memory = memory.copy(activeInstructions =
                (memory.activeInstructions.filterNot { it.equals(rule, ignoreCase = true) } + rule).takeLast(limit))
        }

        if (!settings.rememberTasks) return MemoryTransition(memory)

        if (checklistRequest.containsMatchIn(text) &&
            Regex("crie|faça|mont[eai]|inicie|quero|preciso|abra", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            memory = memory.copy(
                checklistActive = true,
                checklistItems = emptyList(),
                checklistMarker = if (text.contains("✅")) "✅" else "✅",
                lastChecklistAction = "created",
            )
            return MemoryTransition(memory, "Checklist criada! Envie as tarefas, por exemplo: Janela.")
        }
        if (!memory.checklistActive) return MemoryTransition(memory)

        if (Regex("^(finalize|encerre|termine|pare|feche) (a |esta |essa )?(check[ -]?list|lista)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        ) {
            return MemoryTransition(memory.copy(checklistActive = false, lastChecklistAction = "closed"),
                "Checklist encerrada. Os itens continuam salvos na memória.")
        }

        if (Regex("^(o )?emoji[.!?]?$", RegexOption.IGNORE_CASE).matches(text) &&
            memory.lastChecklistAction == "marker"
        ) {
            memory = memory.copy(checklistMarker = "🆗")
            return MemoryTransition(memory, renderChecklist(memory))
        }

        if (Regex("^(coloque|troque|mude|substitua|altere).*(lugar do|marcador|emoji|ponto|símbolo)",
                RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            val marker = when {
                Regex("emoji|okay|ok|🆗", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "🆗"
                text.contains("✅") || text.contains("check", true) -> "✅"
                text.contains("⭐") || text.contains("estrela", true) -> "⭐"
                text.contains("🔵") -> "🔵"
                else -> memory.checklistMarker
            }
            memory = memory.copy(checklistMarker = marker, lastChecklistAction = "marker")
            return MemoryTransition(memory, renderChecklist(memory))
        }

        if (Regex("^(mostre|exiba|liste|qual é|como está).*(lista|checklist|tarefas)",
                RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) return MemoryTransition(memory, renderChecklist(memory))

        val candidate = text.removePrefix("🆗").trimStart('.', '-', '•', '✅', ' ').trim()
            .trimEnd('.', '!', ' ').trim()
        val clearlyTask = text.startsWith(".") || text.startsWith("•") ||
            text.endsWith(".") || (candidate.split(' ').size <= 4 && !text.endsWith("?"))
        if (clearlyTask && candidate.isNotBlank() && candidate.length <= 120 &&
            candidate.split(' ').size <= 9 && !checklistCommands.containsMatchIn(candidate) &&
            !candidate.contains('?')
        ) {
            val entries = if (memory.checklistItems.any { it.equals(candidate, ignoreCase = true) })
                memory.checklistItems else (memory.checklistItems + candidate).takeLast(100)
            memory = memory.copy(checklistItems = entries, lastChecklistAction = "added")
            return MemoryTransition(memory, renderChecklist(memory))
        }
        return MemoryTransition(memory)
    }

    fun renderChecklist(state: ConversationMemory): String =
        if (state.checklistItems.isEmpty()) "Checklist vazia. Envie a primeira tarefa."
        else "Checklist:\n" + state.checklistItems.joinToString("\n") { "${state.checklistMarker} $it" }

    /** Resumo extrativo incremental; não chama outro modelo nem inventa acontecimentos. */
    fun summarize(state: ConversationMemory, history: List<Message>, settings: AiSettings): ConversationMemory {
        if (!settings.autoSummarize) return state
        val completed = history.filter { it.completed && it.content.isNotBlank() && !it.content.startsWith("⚠") }
        val older = completed.dropLast(settings.contextMessages)
        if (older.isEmpty()) return state
        val cursor = older.indexOfLast { it.id == state.summarizedThroughId }
        val newOlder = older.drop(cursor + 1)
        if (newOlder.isEmpty()) return state

        val length = when (settings.memoryProfile) {
            MemoryProfile.LIGHT -> 380
            MemoryProfile.BALANCED -> 650
            MemoryProfile.DEEP -> 1100
        }
        val lines = newOlder.map { message ->
            val who = if (message.isUser) "Pessoa" else if (message.modelName == VisionModelRepository.NAME) "Visão" else "Tutor"
            "$who: ${message.content.replace(Regex("\\s+"), " ").take(155)}"
        }
        val compact = (listOf(state.summary) + lines).filter { it.isNotBlank() }.joinToString("\n")
        return state.copy(summary = compact.takeLast(length), summarizedThroughId = older.last().id)
    }
}
