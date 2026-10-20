package com.example.llama

import android.Manifest
import android.annotation.SuppressLint
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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.CountDownTimer
import android.os.Handler
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Opt-in floating assistant. The microphone stays closed while hibernating;
 * tapping the bubble starts a finite listening window after the greeting.
 * No audio is stored, transcribed, or transmitted.
 */
class PocketBubbleService : Service() {
    private val manager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bubble: TextView? = null
    private var recorder: AudioRecord? = null
    private var microphoneThread: Thread? = null
    @Volatile private var listening = false
    private val mainHandler by lazy { Handler(mainLooper) }
    private var listeningTimer: CountDownTimer? = null
    private var greetingFallback: Runnable? = null
    private var voiceState = VoiceState.HIBERNATING
    private var greetingTts: TextToSpeech? = null
    private var ttsReady = false
    private var greetingPending = false
    private enum class VoiceState { HIBERNATING, GREETING, LISTENING }

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
            }
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (voiceState == VoiceState.LISTENING) intArrayOf(0xFFDD4E60.toInt(), 0xFFF48C5C.toInt())
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
        voiceState = VoiceState.LISTENING
        updateBubbleAppearance()
        beginListening()
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
        stopListening()
        greetingTts?.stop()
        updateBubbleAppearance()
    }

    /** Only this public, fixed greeting uses the Android system voice; no chat text is shared. */
    private fun prepareGreetingVoice() {
        if (greetingTts != null) return
        greetingTts = TextToSpeech(this, { result ->
            val languageState = if (result == TextToSpeech.SUCCESS)
                greetingTts?.setLanguage(Locale("pt", "BR")) else null
            ttsReady = languageState != null &&
                languageState != TextToSpeech.LANG_MISSING_DATA &&
                languageState != TextToSpeech.LANG_NOT_SUPPORTED
            if (ttsReady && greetingPending && voiceState == VoiceState.GREETING) {
                greetingPending = false
                speakFixedGreeting()
            }
        })
        greetingTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finishedGreeting(utteranceId)
            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String) = finishedGreeting(utteranceId)
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

    @SuppressLint("MissingPermission")
    private fun beginListening() {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return
        try {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes * 2, 4096)
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                return
            }
            audioRecord.startRecording()
            recorder = audioRecord
            listening = true
            microphoneThread = Thread({
                val pcm = ShortArray(1024)
                while (listening && !Thread.currentThread().isInterrupted) {
                    val count = audioRecord.read(pcm, 0, pcm.size)
                    if (count <= 0) break
                    // PCM is intentionally discarded. Integrate an in-app offline STT
                    // before treating sound as a recognized user request.
                }
            }, "eduagent-timed-listening").apply { start() }
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            listening = false
        }
    }

    private fun stopListening() {
        listening = false
        microphoneThread?.interrupt()
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        microphoneThread = null
        recorder = null
    }

    override fun onDestroy() {
        hibernate()
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
        const val ACTION_START = "com.eduagent.localchat.START_POCKET_BUBBLE"
        const val ACTION_STOP = "com.eduagent.localchat.STOP_POCKET_BUBBLE"
        const val PREF_ACTIVE = "active"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val CHANNEL_ID = "pocket_bubble_listening"
        private const val NOTIFICATION_ID = 301
        private const val SAMPLE_RATE = 16_000
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
