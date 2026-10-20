package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPhraseChunkerTest {
    @Test fun doesNotSpeakPartialTokensOrWords() {
        val chunks = SpeechPhraseChunker()
        assertTrue(chunks.append("A ja").isEmpty())
        assertTrue(chunks.append("nela está aber").isEmpty())
        assertEquals(listOf("A janela está aberta."), chunks.append("ta."))
        assertTrue(chunks.finish().isEmpty())
    }

    @Test fun speaksBeforeQwenCompletesWholeAnswer() {
        val chunks = SpeechPhraseChunker()
        assertEquals(listOf("Primeiro vamos olhar a foto."),
            chunks.append("Primeiro vamos olhar a foto. Ainda vou explicar"))
        assertEquals(listOf("Ainda vou explicar a segunda parte"),
            chunks.append(" a segunda parte").let { first -> first + chunks.finish() })
    }

    @Test fun splitsLongUnpunctuatedOutputAndResetsBetweenPrompts() {
        val chunks = SpeechPhraseChunker()
        val first = chunks.append("uma palavra ".repeat(13))
        assertTrue(first.isNotEmpty())
        assertTrue(first.first().length <= SpeechPhraseChunker.MAX_CHARS)
        chunks.clear()
        assertEquals(listOf("Nova frase inteira."), chunks.append("Nova frase inteira."))
    }
}
