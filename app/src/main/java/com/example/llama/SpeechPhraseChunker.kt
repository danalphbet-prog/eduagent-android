package com.example.llama

/**
 * Accumulates text-model deltas until a speakable phrase is available.
 * Neither a model token nor a network chunk is necessarily a complete word.
 */
internal class SpeechPhraseChunker {
    private val buffer = StringBuilder()

    fun append(delta: String): List<String> {
        buffer.append(delta)
        val result = mutableListOf<String>()
        while (buffer.isNotEmpty()) {
            val end = buffer.indices.firstOrNull { index ->
                buffer[index] in ".!?;:\n" &&
                    (index >= 9 || buffer[index] == '\n')
            }
            val split = when {
                end != null -> end + 1
                buffer.length >= MAX_CHARS -> {
                    val wordEnd = buffer.lastIndexOf(' ', MAX_CHARS)
                    if (wordEnd >= 45) wordEnd + 1 else MAX_CHARS
                }
                else -> break
            }
            val phrase = buffer.substring(0, split).trim()
            buffer.delete(0, split)
            if (phrase.isNotBlank()) result.add(phrase)
        }
        return result
    }

    fun finish(): List<String> = buildList {
        addAll(append(""))
        val tail = buffer.toString().trim()
        if (tail.isNotEmpty()) add(tail)
        this@SpeechPhraseChunker.clear()
    }

    fun clear() = buffer.clear().let { Unit }

    companion object {
        const val MAX_CHARS = 115
    }
}
