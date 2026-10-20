package com.example.llama

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/** Streams complete phrases into an installed Pocket TTS Android engine. */
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
            onStatus("Pocket TTS não instalado. Abra Configurações > Voz Pocket TTS.")
            return false
        }
        val current = session
        val listener = TextToSpeech.OnInitListener { result ->
            main.post {
                if (current != session) return@post
                val tts = synthesizer ?: return@post
                if (result != TextToSpeech.SUCCESS) {
                    onStatus("Falha ao iniciar Pocket TTS; confira o aplicativo e o modelo.")
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
                } else onStatus("Instale e selecione o pacote português no Pocket TTS.")
            }
        }
        synthesizer = TextToSpeech(appContext, listener, ENGINE_PACKAGE)
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
        const val ENGINE_PACKAGE = "org.pockettts.android.engine"
        const val ENGINE_APK_URL = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.3/PocketTTS-Android-Engine-0.5.3.apk"
        const val PORTUGUESE_PACK_URL = "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.3/PocketTTS-portuguese-FP32.zip"

        fun isInstalled(context: Context): Boolean =
            context.packageManager.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(ENGINE_PACKAGE), 0
            ).isNotEmpty()
    }
}
