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

/** Replacing this implementation with Firebase later will not affect the chat UI. */
interface ChatRepository {
    suspend fun load(): List<Message>
    suspend fun save(messages: List<Message>)
    suspend fun mergeAndSave(messages: List<Message>)
}

class LocalChatRepository(context: Context) : ChatRepository {
    private val history = File(context.filesDir, "chat_history_v1.json")
    // Activity and overlay service create separate repositories. One process-wide gate
    // prevents their independent load/save operations from dropping newer turns.
    private val lock get() = fileGate

    override suspend fun load(): List<Message> = withContext(Dispatchers.IO) {
        lock.withLock { readUnlocked() }
    }

    override suspend fun save(messages: List<Message>) = withContext(Dispatchers.IO) {
        lock.withLock { writeUnlocked(messages) }
    }

    override suspend fun mergeAndSave(messages: List<Message>) = withContext(Dispatchers.IO) {
        lock.withLock {
            val merged = readUnlocked().associateBy { it.id }.toMutableMap()
            messages.forEach { incoming ->
                val saved = merged[incoming.id]
                // A late snapshot must never downgrade a completed answer to partial.
                if (saved == null || (!saved.completed || incoming.completed) &&
                    (incoming.completed || incoming.content.length >= saved.content.length)) {
                    merged[incoming.id] = incoming
                }
            }
            writeUnlocked(merged.values.sortedWith(compareBy<Message> { it.createdAt }.thenBy { it.id }))
        }
    }

    private fun readUnlocked(): List<Message> {
        if (!history.exists()) return emptyList()
        val array = JSONArray(history.readText())
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val stats = obj.optJSONObject("metrics")?.let { m ->
                MessageMetrics(
                    m.optLong("promptTokens"), m.optLong("outputTokens"),
                    m.optLong("prefillMs"), m.optLong("firstTokenMs"), m.optLong("totalMs")
                )
            }
            Message(
                id = obj.getString("id"),
                content = obj.getString("content"),
                isUser = obj.getBoolean("isUser"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                metrics = stats,
                completed = obj.optBoolean("completed", true),
                attachmentPath = obj.optString("attachmentPath").takeIf { it.isNotBlank() },
                modelName = obj.optString("modelName").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun writeUnlocked(messages: List<Message>) {
            val array = JSONArray()
            messages.forEach { msg ->
                val obj = JSONObject()
                    .put("id", msg.id)
                    .put("content", msg.content)
                    .put("isUser", msg.isUser)
                    .put("createdAt", msg.createdAt)
                    .put("completed", msg.completed)
                msg.attachmentPath?.let { obj.put("attachmentPath", it) }
                msg.modelName?.let { obj.put("modelName", it) }
                msg.metrics?.let { m ->
                    obj.put("metrics", JSONObject()
                        .put("promptTokens", m.promptTokens)
                        .put("outputTokens", m.outputTokens)
                        .put("prefillMs", m.prefillMs)
                        .put("firstTokenMs", m.firstTokenMs)
                        .put("totalMs", m.totalMs))
                }
                array.put(obj)
            }
            val tmp = File(history.parentFile, "chat_history_v1.tmp")
            tmp.writeText(array.toString())
            Files.move(tmp.toPath(), history.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Unit
    }

    companion object {
        private val fileGate = Mutex()
    }
}
