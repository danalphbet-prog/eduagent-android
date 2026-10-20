package com.example.llama

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import org.pockettts.android.engine.EmbeddedPocketTts

/** Streams completed phrases to the Pocket TTS engine embedded in this APK. */
class PocketSpeechController(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var synthesizer: TextToSpeech? = null
    private var ready = false
    private var session = 0
    private val pending = ArrayDeque<String>()
    private val chunker = SpeechPhraseChunker()

    fun begin(): Boolean {
        cancel()
        if (!isInstalled(appContext)) {
            onStatus("Baixe o modelo Pocket TTS na engrenagem do EduAgent.")
            return false
        }
        val current = session
        val listener = TextToSpeech.OnInitListener { result ->
            main.post {
                if (current != session) return@post
                val tts = synthesizer ?: return@post
                if (result != TextToSpeech.SUCCESS) {
                    onStatus("Falha ao iniciar Pocket TTS; confira os pesos de voz.")
                    return@post
                }
                // Portuguese packs may advertise pt-PT, not pt-BR.
                val br = tts.setLanguage(Locale("pt", "BR"))
                val selected = if (br == TextToSpeech.LANG_MISSING_DATA ||
                    br == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale("pt", "PT")) else br
                ready = selected != TextToSpeech.LANG_MISSING_DATA &&
                    selected != TextToSpeech.LANG_NOT_SUPPORTED
                if (ready) {
                    onStatus("Motor Pocket conectado · iniciando síntese")
                    drain()
                } else onStatus("Baixe e selecione o pacote português na engrenagem.")
            }
        }
        // Service and JNI runtime are packaged inside EduAgent itself.
        synthesizer = TextToSpeech(appContext, listener, appContext.packageName)
        synthesizer?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = Unit

            @Deprecated("API do Android")
            override fun onError(utteranceId: String) {
                main.post {
                    if (current == session) onStatus(
                        "Pocket TTS falhou ao falar. Confira o modelo e a voz selecionada."
                    )
                }
            }
        })
        return true
    }

    /** Called for every incremental Qwen text chunk (not once per completed answer). */
    fun append(delta: String) {
        chunker.append(delta).forEach { pending.add(it) }
        drain()
    }

    fun finish() {
        chunker.finish().forEach { pending.add(it) }
        drain()
    }

    fun cancel() {
        session++
        ready = false
        chunker.clear()
        pending.clear()
        synthesizer?.stop()
        synthesizer?.shutdown()
        synthesizer = null
    }

    private fun drain() {
        if (!ready) return
        val tts = synthesizer ?: return
        while (pending.isNotEmpty()) {
            val phrase = pending.removeFirst()
            val outcome = tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            if (outcome == TextToSpeech.ERROR) {
                onStatus("Não foi possível sintetizar com Pocket TTS.")
                return
            }
        }
    }

    companion object {
        fun isInstalled(context: Context): Boolean =
            EmbeddedPocketTts.isReady(context)
    }
}
