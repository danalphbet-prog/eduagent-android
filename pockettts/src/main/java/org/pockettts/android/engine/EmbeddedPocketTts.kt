package org.pockettts.android.engine

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Public interface used by EduAgent; the speech engine is in the same APK. */
object EmbeddedPocketTts {
    const val PORTUGUESE_URL =
        "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.3/PocketTTS-portuguese-FP32.zip"
    const val PORTUGUESE_SHA256 = "4d1891c5acb5d9ecb6d1a8c3e5f2d57ee730b9408715d66ab1a3d640dedc30e9"
    private const val MAX_DOWNLOAD_BYTES = 400L * 1024L * 1024L

    fun isReady(context: Context): Boolean =
        ModelPackRepository.selectedPack(context)?.voices?.isNotEmpty() == true

    fun selectedModel(context: Context): String? = ModelPackRepository.selectedPack(context)?.displayName

    fun configureIntent(context: Context): Intent = Intent(context, MainActivity::class.java)

    /** Download a pinned, verified model into the EduAgent private storage. */
    suspend fun downloadPortuguese(context: Context, onProgress: (Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val staging = File(app.cacheDir, "pockettts-portuguese-fp32-v053.zip")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = URL(PORTUGUESE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            try {
                require(connection.responseCode in 200..299) {
                    "Download Pocket: HTTP ${connection.responseCode}"
                }
                val expectedSize = connection.contentLengthLong
                require(expectedSize <= MAX_DOWNLOAD_BYTES) { "Tamanho do pacote inesperado" }
                var total = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    staging.outputStream().buffered().use { output ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_DOWNLOAD_BYTES) { "Pacote excedeu o tamanho permitido" }
                            output.write(bytes, 0, count)
                            digest.update(bytes, 0, count)
                            if (expectedSize > 0) {
                                val percent = (total * 100L / expectedSize).toInt().coerceIn(0, 99)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
                require(total > 0L && (expectedSize < 0L || total == expectedSize)) {
                    "Download incompleto"
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                require(sha256.equals(PORTUGUESE_SHA256, ignoreCase = true)) {
                    "SHA-256 do modelo não confere; importação cancelada"
                }
                withContext(Dispatchers.Main) { onProgress(100) }
                val installed = ModelPackRepository.importDownloadedPack(app, staging)
                installed.displayName
            } finally {
                connection.disconnect()
                staging.delete()
            }
        }
}
