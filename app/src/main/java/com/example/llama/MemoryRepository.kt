package com.example.llama

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Estado durável da memória; compartilhado entre o chat e o serviço de bolha. */
class MemoryRepository(context: Context) {
    private val file = File(context.filesDir, "conversation_memory_v1.json")

    suspend fun load(history: List<Message> = emptyList(), settings: AiSettings = AiSettings()): ConversationMemory =
        withContext(Dispatchers.IO) {
            gate.withLock { readOrBootstrap(history, settings) }
        }

    suspend fun process(question: String, history: List<Message>, settings: AiSettings): MemoryTransition =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val before = MemoryEngine.summarize(readOrBootstrap(history, settings), history, settings)
                val result = MemoryEngine.onUserInput(before, question, settings)
                write(result.state)
                result
            }
        }

    suspend fun captureHistory(history: List<Message>, settings: AiSettings) = withContext(Dispatchers.IO) {
        gate.withLock {
            write(MemoryEngine.summarize(readOrBootstrap(history, settings), history, settings))
        }
    }

    private fun readOrBootstrap(history: List<Message>, settings: AiSettings): ConversationMemory {
        if (file.exists()) return decode(JSONObject(file.readText()))
        // On upgrade from 0.2, recover rules/checklists from locally stored user turns.
        val recovered = history.filter { it.isUser && it.completed }.takeLast(120)
            .fold(ConversationMemory()) { state, message ->
                MemoryEngine.onUserInput(state, message.content, settings).state
            }
        val initial = MemoryEngine.summarize(recovered, history, settings)
        write(initial)
        return initial
    }

    private fun decode(json: JSONObject): ConversationMemory {
        val rules = json.optJSONArray("activeInstructions") ?: JSONArray()
        val items = json.optJSONArray("checklistItems") ?: JSONArray()
        return ConversationMemory(
            activeInstructions = (0 until rules.length()).map { rules.optString(it) },
            checklistActive = json.optBoolean("checklistActive", false),
            checklistItems = (0 until items.length()).map { items.optString(it) },
            checklistMarker = json.optString("checklistMarker", "✅"),
            lastChecklistAction = json.optString("lastChecklistAction", ""),
            summary = json.optString("summary", ""),
            summarizedThroughId = json.optString("summarizedThroughId").takeIf { it.isNotBlank() },
        )
    }

    private fun write(state: ConversationMemory) {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("activeInstructions", JSONArray(state.activeInstructions))
            .put("checklistActive", state.checklistActive)
            .put("checklistItems", JSONArray(state.checklistItems))
            .put("checklistMarker", state.checklistMarker)
            .put("lastChecklistAction", state.lastChecklistAction)
            .put("summary", state.summary)
            .put("summarizedThroughId", state.summarizedThroughId ?: "")
        val tmp = File(file.parentFile, "conversation_memory_v1.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val gate = Mutex()
    }
}
