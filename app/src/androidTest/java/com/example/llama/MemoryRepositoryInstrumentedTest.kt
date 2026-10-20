package com.example.llama

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryInstrumentedTest {
    @Test fun activeChecklistAndSummarySurviveRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDir = File(context.cacheDir, "test-memory-${UUID.randomUUID()}").apply { mkdirs() }
        val sandbox = object : ContextWrapper(context) {
            override fun getFilesDir(): File = testDir
        }
        try {
            val prefs = AiSettings(contextMessages = 2)
            val memory = MemoryRepository(sandbox)
            memory.process("Crie uma checklist", emptyList(), prefs)
            memory.process("Janela.", emptyList(), prefs)
            val oldTurns = (1..7).map { i -> Message("$i", "Mensagem $i", i % 2 == 0) }
            memory.captureHistory(oldTurns, prefs)
            val restored = MemoryRepository(sandbox).load(oldTurns, prefs)
            assertEquals(listOf("Janela"), restored.checklistItems)
            assertTrue(restored.checklistActive)
            assertTrue(restored.summary.contains("Mensagem"))
            assertEquals("5", restored.summarizedThroughId)
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test fun parallelChatWritesKeepBothMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDir = File(context.cacheDir, "test-chat-${UUID.randomUUID()}").apply { mkdirs() }
        val sandbox = object : ContextWrapper(context) {
            override fun getFilesDir(): File = testDir
        }
        try {
            val first = LocalChatRepository(sandbox)
            val second = LocalChatRepository(sandbox)
            listOf(
                async { first.mergeAndSave(listOf(Message("a", "A", true))) },
                async { second.mergeAndSave(listOf(Message("b", "B", true))) },
            ).awaitAll()
            assertEquals(setOf("a", "b"), first.load().map { it.id }.toSet())
        } finally {
            testDir.deleteRecursively()
        }
    }
}
