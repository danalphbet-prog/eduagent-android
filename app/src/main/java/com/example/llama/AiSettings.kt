package com.example.llama

import android.content.Context

/** Local, user-adjustable generation limits. No preferences leave this device. */
data class AiSettings(
    val qwenMaxTokens: Int = 160,
    val visionMaxTokens: Int = 80,
    val temperature: Float = 0.3f,
    val contextMessages: Int = 8,
    val sharedContext: Boolean = true,
    val conciseAnswers: Boolean = true,
    val reuseLastImage: Boolean = true,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("qwenMaxTokens", qwenMaxTokens.coerceIn(32, 512))
            .putInt("visionMaxTokens", visionMaxTokens.coerceIn(16, 256))
            .putFloat("temperature", temperature.coerceIn(0f, 1f))
            .putInt("contextMessages", contextMessages.coerceIn(2, 16))
            .putBoolean("sharedContext", sharedContext)
            .putBoolean("conciseAnswers", conciseAnswers)
            .putBoolean("reuseLastImage", reuseLastImage)
            .apply()
    }

    companion object {
        private const val PREFS = "eduagent_generation_settings_v1"

        fun load(context: Context): AiSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AiSettings(
                qwenMaxTokens = prefs.getInt("qwenMaxTokens", 160).coerceIn(32, 512),
                visionMaxTokens = prefs.getInt("visionMaxTokens", 80).coerceIn(16, 256),
                temperature = prefs.getFloat("temperature", 0.3f).coerceIn(0f, 1f),
                contextMessages = prefs.getInt("contextMessages", 8).coerceIn(2, 16),
                sharedContext = prefs.getBoolean("sharedContext", true),
                conciseAnswers = prefs.getBoolean("conciseAnswers", true),
                reuseLastImage = prefs.getBoolean("reuseLastImage", true),
            )
        }
    }
}
