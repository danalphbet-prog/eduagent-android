package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEngineTest {
    private val deep = AiSettings(
        memoryProfile = MemoryProfile.DEEP,
        keepActiveInstructions = true,
        rememberTasks = true,
        autoSummarize = true,
    )

    @Test fun checklistRecreatesReportedConversation() {
        var state = ConversationMemory()
        val prompts = listOf(
            "Crie uma check-list pra mim. Todas as tarefas que eu mandar que tiverem com . no começo coloque ✅ no lugar do ponto",
            "Janela.",
            "Coloque o okay no lugar do ponto",
            "O emoji",
        )
        val replies = prompts.map { question ->
            MemoryEngine.onUserInput(state, question, deep).also { state = it.state }.directReply.orEmpty()
        }
        assertTrue(replies[0].contains("Checklist criada"))
        assertEquals(listOf("Janela"), state.checklistItems)
        assertTrue(replies[1].contains("✅ Janela"))
        assertTrue(replies[2].contains("🆗 Janela"))
        assertTrue(replies[3].contains("🆗 Janela"))
        assertEquals("🆗", state.checklistMarker)

        val next = MemoryEngine.onUserInput(state, "Porta.", deep)
        assertEquals(listOf("Janela", "Porta"), next.state.checklistItems)
        assertTrue(next.directReply!!.contains("🆗 Porta"))
        assertEquals("🆗 Janela", next.directReply.lines()[1])
    }

    @Test fun instructionSurvivesUnrelatedTurnsAndReachesBothModels() {
        val pinned = MemoryEngine.onUserInput(ConversationMemory(),
            "A partir de agora responda sempre em português simples.", deep).state
        val afterOther = MemoryEngine.onUserInput(pinned, "Qual é a capital?", deep).state
        assertEquals(pinned.activeInstructions, afterOther.activeInstructions)
        val history = listOf(
            Message("1", "Explique o exercício", true),
            Message("2", "Vamos resolver juntos", false),
        )
        val qwen = ConversationBridge.forQwen(history, "E agora?", deep, afterOther)
        val vision = ConversationBridge.forVision(history, "Leia a foto", deep, afterOther)
        assertTrue(qwen.contains("A partir de agora responda sempre"))
        assertTrue(qwen.contains("Explique o exercício"))
        assertTrue(qwen.contains("Vamos resolver juntos"))
        assertTrue(vision.contains("A partir de agora responda sempre"))
        assertTrue(vision.contains("Explique o exercício"))
    }

    @Test fun extractiveSummaryHasCursorAndDoesNotDuplicate() {
        val history = (1..24).map { i ->
            Message("m$i", "Turno de teste número $i", i % 2 == 1, createdAt = i.toLong())
        }
        val summary = MemoryEngine.summarize(ConversationMemory(), history, deep)
        assertEquals("m12", summary.summarizedThroughId)
        assertTrue(summary.summary.contains("Turno de teste número 1"))
        assertEquals(summary, MemoryEngine.summarize(summary, history, deep))
        val after = MemoryEngine.summarize(summary, history + Message("m25", "Nova lembrança", true), deep)
        assertEquals("m13", after.summarizedThroughId)
        assertTrue(after.summary.length <= 1100)
    }

    @Test fun tasksCanBeDisabledWithoutDisablingNormalConversation() {
        val off = deep.copy(rememberTasks = false)
        val transition = MemoryEngine.onUserInput(ConversationMemory(), "Crie uma checklist", off)
        assertFalse(transition.state.checklistActive)
        assertEquals(null, transition.directReply)
    }

    @Test fun recentVisualTranscriptionIsGivenToQwen() {
        val messages = listOf(
            Message("a", "@VLM O que está na imagem?", true),
            Message("b", "Há uma janela azul na foto.", false, modelName = VisionModelRepository.NAME),
            Message("c", "O que significa azul?", true),
        )
        val context = ConversationBridge.forQwen(messages, "E a janela?", deep)
        assertTrue(context.contains("Há uma janela azul na foto"))
        assertTrue(context.contains("E a janela?"))
    }

    @Test fun ragRecoversOldRelevantConversationBeyondRecentWindow() {
        val history = listOf(
            Message("u1", "Nosso projeto Bubble conecta jogadores Bedrock de mundos separados.", true),
            Message("a1", "Combinamos enviar posições à API Bubble para sincronizar os avatares.", false),
            Message("u2", "Vamos estudar alfabetização hoje?", true),
            Message("a2", "Podemos começar com sílabas.", false),
            Message("u3", "Obrigado!", true),
            Message("a3", "De nada!", false),
        )
        val settings = deep.copy(contextMessages = 2, ragResults = 2)
        val matches = RagRetriever.retrieve(history, "O que definimos para conectar os jogadores Bedrock?", settings)
        assertTrue(matches.isNotEmpty())
        assertEquals("u1", matches.first().sourceId)
        assertTrue(matches.first().text.contains("posições à API Bubble"))
        val prompt = ConversationBridge.forQwen(history, "O que definimos para conectar os jogadores Bedrock?", settings)
        assertTrue(prompt.contains("RAG — TRECHOS ANTIGOS"))
        assertTrue(prompt.contains("Nosso projeto Bubble"))
    }

    @Test fun ragCanBeDisabled() {
        val history = listOf(
            Message("old", "Projeto muito específico de astronomia", true),
            Message("new", "Conversa recente", false),
        )
        assertTrue(RagRetriever.retrieve(history, "astronomia", deep.copy(
            contextMessages = 1, ragEnabled = false)).isEmpty())
    }
}
