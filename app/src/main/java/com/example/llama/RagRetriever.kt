package com.example.llama

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

data class RagExcerpt(val sourceId: String, val text: String, val score: Double)

/**
 * RAG local, leve e sem embeddings: pesquisa BM25 no histórico já salvo.
 * Recupera assuntos antigos que ficaram fora da janela recente do Qwen.
 * A indexação é efêmera; a fonte persistida é chat_history_v1.json.
 */
object RagRetriever {
    private val tokenPattern = Regex("[a-z0-9]{3,}")
    private val accents = Regex("\\p{M}+")
    private val stopwords = setOf(
        "que", "com", "para", "por", "como", "qual", "quais", "quando", "onde", "quem", "isso",
        "essa", "esse", "estes", "estas", "sobre", "uma", "uns", "umas", "meu", "minha", "seu",
        "sua", "nosso", "nossa", "aqui", "agora", "depois", "mais", "menos", "fazer", "pode",
        "poder", "voce", "voces", "quero", "preciso", "seria", "estou", "esta", "estao",
        "tinha", "temos", "entao", "tambem", "entre", "desde", "antes", "mesmo", "outro",
        "outra", "ainda", "todas", "todos", "pois", "porque", "cada", "muito", "pouco",
        "esse", "aquela", "aquele", "fale", "lembre", "lembra", "historia", "historico",
    )

    fun retrieve(messages: List<Message>, question: String, settings: AiSettings): List<RagExcerpt> {
        if (!settings.ragEnabled) return emptyList()
        val queryTerms = terms(question)
        if (queryTerms.isEmpty()) return emptyList()
        val recentCount = when (settings.memoryProfile) {
            MemoryProfile.LIGHT -> settings.contextMessages.coerceAtMost(4)
            MemoryProfile.BALANCED -> settings.contextMessages.coerceAtMost(8)
            MemoryProfile.DEEP -> settings.contextMessages
        }
        val older = messages.filter { it.completed && it.content.isNotBlank() && !it.content.startsWith("⚠") }
            .dropLast(recentCount)
        if (older.isEmpty()) return emptyList()

        val passages = buildList {
            var pos = 0
            while (pos < older.size) {
                val message = older[pos]
                if (message.isUser) {
                    val next = older.getOrNull(pos + 1)?.takeIf { !it.isUser }
                    val body = "Pessoa: ${compact(message.content, 190)}" +
                        (next?.let { "\nEduAgent: ${compact(it.content, 270)}" } ?: "")
                    add(RagExcerpt(message.id, body, 0.0))
                    pos += if (next == null) 1 else 2
                } else {
                    add(RagExcerpt(message.id, "EduAgent: ${compact(message.content, 270)}", 0.0))
                    pos++
                }
            }
        }
        if (passages.isEmpty()) return emptyList()
        val frequencies = passages.map { passage -> terms(passage.text).groupingBy { it }.eachCount() }
        val docFreq = mutableMapOf<String, Int>()
        frequencies.forEach { count -> count.keys.forEach { token -> docFreq[token] = (docFreq[token] ?: 0) + 1 } }
        val avgLength = frequencies.map { it.values.sum() }.average().coerceAtLeast(1.0)
        val max = settings.ragResults.coerceIn(1, 6)

        return passages.mapIndexed { index, passage ->
            val freq = frequencies[index]
            val length = freq.values.sum().toDouble()
            var score = 0.0
            queryTerms.distinct().forEach { term ->
                val occurrences = freq[term]?.toDouble() ?: 0.0
                if (occurrences > 0) {
                    val idf = ln(1.0 + (passages.size - (docFreq[term] ?: 0) + 0.5) / ((docFreq[term] ?: 0) + 0.5))
                    score += idf * (occurrences * 2.2) / (occurrences + 1.2 * (0.25 + 0.75 * length / avgLength))
                }
            }
            // Small recency tie-break. Relevance remains the primary criterion.
            if (score > 0) score += index.toDouble() / passages.size * 0.06
            passage.copy(score = score)
        }.filter { it.score > 0.15 }
            .sortedByDescending { it.score }
            .take(max)
    }

    private fun terms(text: String): List<String> {
        val normalized = accents.replace(
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), ""
        )
        return tokenPattern.findAll(normalized).map { it.value }
            .filterNot { it in stopwords }
            .map { word ->
                when {
                    word.endsWith("coes") && word.length >= 7 -> word.dropLast(4) + "cao"
                    word.endsWith("s") && word.length > 4 -> word.dropLast(1)
                    else -> word
                }
            }.toList()
    }

    private fun compact(value: String, chars: Int): String =
        value.replace(Regex("\\s+"), " ").take(chars)
}
