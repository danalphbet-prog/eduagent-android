package com.example.llama

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.os.CountDownTimer
import android.os.Handler
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID

/**
 * Opt-in floating assistant. Microphone is closed while hibernating. A tap
 * triggers a fixed greeting, a 10s on-device recognition window, local Qwen
 * inference, and speech synthesis in sentence-sized chunks. No raw audio is
 * saved or sent to a server. Prefers a locally installed Kyutai Pocket TTS
 * system engine; self-contained Pocket model weights are not bundled yet.
 */
class PocketBubbleService : Service() {
    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bubble: TextView? = null
    private var recognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var replyJob: Job? = null
    private val mainHandler by lazy { Handler(mainLooper) }
    private var listeningTimer: CountDownTimer? = null
    private var greetingFallback: Runnable? = null
    private var voiceState = VoiceState.HIBERNATING
    private var greetingTts: TextToSpeech? = null
    private var ttsReady = false
    private var greetingPending = false
    private var usingPocketVoice = false
    private var speechPiecesPending = 0
    private var finishedGenerating = false
    private var queuedAnyReply = false
    private var speechSequence = 0
    private enum class VoiceState { HIBERNATING, GREETING, LISTENING, THINKING, SPEAKING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            preferences(this).edit().putBoolean(PREF_ACTIVE, true).apply()
            if (bubble == null) showBubble()
            prepareGreetingVoice()
            updateBubbleAppearance()
        } catch (e: Exception) {
            stopSelf()
        }
        // Do not relaunch microphone FGS from background after process termination.
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Assistente flutuante · microfone", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Bolha flutuante: microfone só depois de tocar"
                setShowBadge(false)
            }
        )
    }

    private fun createNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, PocketBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("EduAgent flutuante ativo")
            .setContentText("Em espera. Toque na bolha para ouvir a saudação e iniciar 10 segundos de escuta.")
            .setContentIntent(openIntent)
            .addAction(0, "Desativar", stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showBubble() {
        val diameter = dp(56)
        val view = TextView(this).apply {
            text = "✦"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Bolha EduAgent. Toque para conversar por 10 segundos. Toque novamente para hibernar. Arraste para mover."
            elevation = dp(8).toFloat()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF496EEB.toInt(), 0xFF9169DC.toInt())).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.WHITE)
            }
        }
        val prefs = preferences(this)
        val params = WindowManager.LayoutParams(
            diameter, diameter,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, resources.displayMetrics.widthPixels - diameter - dp(12))
            y = prefs.getInt(PREF_Y, dp(170))
        }
        view.setOnTouchListener(object : View.OnTouchListener {
            private var xStart = 0
            private var yStart = 0
            private var pointerX = 0f
            private var pointerY = 0f
            private var moved = false

            override fun onTouch(touched: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        xStart = params.x
                        yStart = params.y
                        pointerX = event.rawX
                        pointerY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - pointerX).toInt()
                        val dy = (event.rawY - pointerY).toInt()
                        if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                        if (moved) {
                            params.x = (xStart + dx).coerceIn(0, (resources.displayMetrics.widthPixels - diameter).coerceAtLeast(0))
                            params.y = (yStart + dy).coerceIn(0, (resources.displayMetrics.heightPixels - diameter).coerceAtLeast(0))
                            runCatching { manager.updateViewLayout(view, params) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            preferences(this@PocketBubbleService).edit()
                                .putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                        } else onBubbleTap()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
        manager.addView(view, params)
        bubble = view
    }

    private fun onBubbleTap() {
        if (voiceState != VoiceState.HIBERNATING) {
            hibernate()
            return
        }
        voiceState = VoiceState.GREETING
        updateBubbleAppearance()
        if (ttsReady) speakFixedGreeting() else greetingPending = true
        // Still open the listening window if there is no usable TTS engine.
        greetingFallback = Runnable {
            if (voiceState == VoiceState.GREETING) beginListeningWindow()
        }.also { mainHandler.postDelayed(it, 3500L) }
    }

    private fun updateBubbleAppearance(secondsLeft: Int = 10) {
        bubble?.apply {
            text = when (voiceState) {
                VoiceState.HIBERNATING -> "✦"
                VoiceState.GREETING -> "●"
                VoiceState.LISTENING -> secondsLeft.toString()
                VoiceState.THINKING -> "…"
                VoiceState.SPEAKING -> "♫"
            }
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (voiceState == VoiceState.LISTENING) intArrayOf(0xFFDD4E60.toInt(), 0xFFF48C5C.toInt())
                else if (voiceState == VoiceState.THINKING || voiceState == VoiceState.SPEAKING)
                    intArrayOf(0xFF279D9B.toInt(), 0xFF546DCB.toInt())
                else intArrayOf(0xFF496EEB.toInt(), 0xFF9169DC.toInt())).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), Color.WHITE)
                }
        }
    }

    private fun beginListeningWindow() {
        if (voiceState != VoiceState.GREETING) return
        greetingFallback?.let { mainHandler.removeCallbacks(it) }
        greetingFallback = null
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            reportUnavailable("Este celular não disponibiliza reconhecimento de fala offline.")
            return
        }
        voiceState = VoiceState.LISTENING
        updateBubbleAppearance()
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onError(error: Int) {
                        if (voiceState == VoiceState.LISTENING) hibernate()
                    }

                    override fun onResults(results: Bundle?) {
                        if (voiceState != VoiceState.LISTENING) return
                        val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (words.isBlank()) hibernate() else answerSpokenQuestion(words)
                    }
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            }
        } catch (e: Exception) {
            reportUnavailable("Não foi possível iniciar a escuta: ${e.message ?: "erro desconhecido"}")
            return
        }
        listeningTimer = object : CountDownTimer(LISTEN_DURATION_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (voiceState == VoiceState.LISTENING)
                    updateBubbleAppearance(((millisUntilFinished + 999) / 1000).toInt())
            }
            override fun onFinish() { hibernate() }
        }.start()
    }

    private fun hibernate() {
        voiceState = VoiceState.HIBERNATING
        greetingPending = false
        greetingFallback?.let { mainHandler.removeCallbacks(it) }
        greetingFallback = null
        listeningTimer?.cancel()
        listeningTimer = null
        stopRecognition()
        replyJob?.cancel()
        replyJob = null
        speechPiecesPending = 0
        finishedGenerating = false
        queuedAnyReply = false
        greetingTts?.stop()
        updateBubbleAppearance()
    }

    private fun stopRecognition() {
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }

    private fun reportUnavailable(reason: String) {
        hibernate()
        android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show()
        if (ttsReady) greetingTts?.speak(reason, TextToSpeech.QUEUE_FLUSH, null, "eduagent_voice_error")
    }

    /** The voice conversation uses the same Qwen model and history as the text chat. */
    private fun answerSpokenQuestion(question: String) {
        listeningTimer?.cancel()
        listeningTimer = null
        stopRecognition()
        voiceState = VoiceState.THINKING
        updateBubbleAppearance()

        replyJob = serviceScope.launch {
            try {
                val historyStore = LocalChatRepository(applicationContext)
                val history = historyStore.load()
                val currentSettings = AiSettings.load(applicationContext)
                val memoryStore = MemoryRepository(applicationContext)
                val transition = memoryStore.process(question, history, currentSettings)
                if (transition.directReply != null) {
                    val answer = transition.directReply
                    val turns = listOf(
                        Message(id = UUID.randomUUID().toString(), content = question, isUser = true),
                        Message(id = UUID.randomUUID().toString(), content = answer,
                            isUser = false, modelName = "Memory Engine"),
                    )
                    historyStore.mergeAndSave(turns)
                    memoryStore.captureHistory(history + turns, currentSettings)
                    speechPiecesPending = 0
                    finishedGenerating = false
                    speakPiece(answer)
                    finishedGenerating = true
                    if (speechPiecesPending == 0) hibernate()
                    return@launch
                }
                val model = ModelRepository(applicationContext).verifiedModelOrNull()
                if (model == null) {
                    reportUnavailable("Abra o EduAgent e baixe o Qwen para conversar por voz.")
                    return@launch
                }

                val engine = AiChat.getInferenceEngine(applicationContext)
                val readyState = withTimeout(30_000L) {
                    engine.state.first {
                        it is InferenceEngine.State.Initialized ||
                            it.isModelLoaded || it is InferenceEngine.State.Error
                    }
                }
                if (readyState is InferenceEngine.State.Error) throw readyState.exception
                if (readyState is InferenceEngine.State.Initialized) {
                    engine.loadModel(model.absolutePath)
                    engine.setSystemPrompt(
                        "Você é EduAgent, tutor gentil e direto. Converse em português do Brasil, " +
                            "com respostas curtas e naturais, adequadas para serem ouvidas. " +
                            "Não invente informações."
                    )
                }
                if (engine.state.value !is InferenceEngine.State.ModelReady) {
                    reportUnavailable("O modelo está ocupado. Tente tocar na bolha novamente.")
                    return@launch
                }
                engine.setTemperature(currentSettings.temperature)

                val prompt = ConversationBridge.forQwen(history, question, currentSettings, transition.state)
                val fullReply = StringBuilder()
                val unsaid = StringBuilder()
                speechPiecesPending = 0
                finishedGenerating = false
                queuedAnyReply = false
                engine.sendUserPrompt(prompt, currentSettings.qwenMaxTokens).collect { part ->
                    if (voiceState == VoiceState.HIBERNATING) throw CancellationException("Bolha pausada")
                    fullReply.append(part)
                    unsaid.append(part)
                    speakCompletedPhrases(unsaid)
                }
                speakRemaining(unsaid)

                val answer = fullReply.toString().trim().ifBlank {
                    "Não consegui gerar uma resposta agora. Tente novamente."
                }
                val turns = listOf(
                    Message(id = UUID.randomUUID().toString(), content = question, isUser = true),
                    Message(id = UUID.randomUUID().toString(), content = answer,
                        isUser = false, modelName = ModelRepository.MODEL_NAME)
                )
                historyStore.mergeAndSave(turns)
                memoryStore.captureHistory(history + turns, currentSettings)
                if (!queuedAnyReply) speakPiece(answer)
                finishedGenerating = true
                if (speechPiecesPending == 0) hibernate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                reportUnavailable("Não consegui responder: ${e.message ?: "erro na IA"}")
            }
        }
    }

    private fun speakCompletedPhrases(buffer: StringBuilder) {
        while (buffer.isNotEmpty()) {
            // Prefer natural pause marks; cap unpunctuated text to keep latency bounded.
            val punctuationAt = buffer.indexOfFirst { it == '.' || it == '!' || it == '?' || it == ';' || it == '\n' }
            val splitAt = when {
                punctuationAt >= 0 -> punctuationAt + 1
                buffer.length >= 160 -> buffer.lastIndexOf(' ', 160).takeIf { it >= 60 } ?: 160
                else -> break
            }
            val phrase = buffer.substring(0, splitAt).trim()
            buffer.delete(0, splitAt)
            if (phrase.isNotBlank()) speakPiece(phrase)
        }
    }

    private fun speakRemaining(buffer: StringBuilder) {
        val phrase = buffer.toString().trim()
        buffer.clear()
        if (phrase.isNotBlank()) speakPiece(phrase)
    }

    private fun speakPiece(phrase: String) {
        if (voiceState == VoiceState.HIBERNATING || !ttsReady) return
        val synthesizer = greetingTts ?: return
        voiceState = VoiceState.SPEAKING
        updateBubbleAppearance()
        val utteranceId = "eduagent_reply_${++speechSequence}"
        speechPiecesPending++
        val status = synthesizer.speak(phrase, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (status == TextToSpeech.ERROR) speechPiecesPending-- else queuedAnyReply = true
    }

    private fun onReplyPartDone(utteranceId: String) {
        if (!utteranceId.startsWith("eduagent_reply_")) return
        mainHandler.post {
            if (voiceState == VoiceState.HIBERNATING) return@post
            speechPiecesPending = (speechPiecesPending - 1).coerceAtLeast(0)
            if (finishedGenerating && speechPiecesPending == 0) hibernate()
        }
    }

    /** Prefers community Pocket TTS engine when a Portuguese voice has been imported. */
    private fun prepareGreetingVoice() {
        if (greetingTts != null) return
        val pocketAvailable = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(POCKET_ENGINE_PACKAGE), 0
        ).isNotEmpty()
        initializeSpeechEngine(if (pocketAvailable) POCKET_ENGINE_PACKAGE else null)
    }

    private fun initializeSpeechEngine(preferred: String?) {
        val listener = TextToSpeech.OnInitListener { result ->
            usingPocketVoice = result == TextToSpeech.SUCCESS &&
                preferred == POCKET_ENGINE_PACKAGE
            val br = if (result == TextToSpeech.SUCCESS)
                greetingTts?.setLanguage(Locale("pt", "BR")) else null
            val languageState = if (br == TextToSpeech.LANG_MISSING_DATA ||
                br == TextToSpeech.LANG_NOT_SUPPORTED)
                greetingTts?.setLanguage(Locale("pt", "PT")) else br
            ttsReady = languageState != null &&
                languageState != TextToSpeech.LANG_MISSING_DATA &&
                languageState != TextToSpeech.LANG_NOT_SUPPORTED
            if ((!ttsReady || !usingPocketVoice) && preferred != null) {
                // Pocket installed but not configured with Portuguese: use a functional
                // system fallback rather than silently claiming Kyutai synthesis.
                greetingTts?.shutdown()
                greetingTts = null
                usingPocketVoice = false
                initializeSpeechEngine(null)
                return@OnInitListener
            }
            if (ttsReady && greetingPending && voiceState == VoiceState.GREETING) {
                greetingPending = false
                speakFixedGreeting()
            }
        }
        greetingTts = if (preferred != null) TextToSpeech(this, listener, preferred)
            else TextToSpeech(this, listener)
        greetingTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                finishedGreeting(utteranceId)
                onReplyPartDone(utteranceId)
            }
            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String) {
                finishedGreeting(utteranceId)
                onReplyPartDone(utteranceId)
            }
        })
    }

    private fun finishedGreeting(utteranceId: String) {
        if (utteranceId == "eduagent_fixed_greeting")
            mainHandler.post { beginListeningWindow() }
    }

    private fun speakFixedGreeting() {
        greetingTts?.speak(
            "Opa, oi, tudo bem? Eu sou o EduAgent. Quer ajuda com alguma coisa?",
            TextToSpeech.QUEUE_FLUSH, null, "eduagent_fixed_greeting"
        )
    }

    override fun onDestroy() {
        hibernate()
        serviceScope.cancel()
        bubble?.let { runCatching { manager.removeView(it) } }
        bubble = null
        greetingTts?.shutdown()
        greetingTts = null
        ttsReady = false
        preferences(this).edit().putBoolean(PREF_ACTIVE, false).apply()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val POCKET_ENGINE_PACKAGE = "org.pockettts.android.engine"
        const val ACTION_START = "com.eduagent.localchat.START_POCKET_BUBBLE"
        const val ACTION_STOP = "com.eduagent.localchat.STOP_POCKET_BUBBLE"
        const val PREF_ACTIVE = "active"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val CHANNEL_ID = "pocket_bubble_listening"
        private const val NOTIFICATION_ID = 301
        private const val LISTEN_DURATION_MS = 10_000L

        fun preferences(context: Context) = context.getSharedPreferences("eduagent_pocket_bubble", Context.MODE_PRIVATE)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PocketBubbleService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PocketBubbleService::class.java))
        }
    }
}
