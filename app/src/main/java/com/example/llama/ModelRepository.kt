package com.example.llama

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloaded once, SHA-256 verified, stored in app-private files. No Debian or root. */
class ModelRepository(private val context: Context) {
    companion object {
        const val MODEL_NAME = "Qwen2.5-1.5B-Instruct Q4_K_M"
        private const val FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
        private const val APPROX_BYTES = 1_120_000_000L
    }

    private val modelDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }
    private val file: File get() = File(modelDir, FILENAME)
    private val partial: File get() = File(modelDir, "$FILENAME.part")
    private val prefs = context.getSharedPreferences("local_models_v1", Context.MODE_PRIVATE)

    suspend fun verifiedModelOrNull(): File? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() < 1_000_000_000L) return@withContext null
        if (prefs.getString("verified_sha", null) == SHA256) return@withContext file
        if (checkSha(file)) {
            prefs.edit().putString("verified_sha", SHA256).apply()
            file
        } else null
    }

    /** The callback runs on a worker thread. Resume survives app restarts via .part. */
    suspend fun download(progress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            verifiedModelOrNull()?.let { return@withContext it }

            val existing = partial.takeIf { it.exists() }?.length() ?: 0L
            val minimumFree = (APPROX_BYTES - existing).coerceAtLeast(0L) + 200_000_000L
            require(modelDir.usableSpace > minimumFree) {
                "Espaço insuficiente. Libere aproximadamente 1,5 GB para instalar a IA."
            }

            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "EduAgent-Android/0.1")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            try {
                val status = conn.responseCode
                require(status == 200 || status == 206) {
                    "Servidor retornou HTTP $status. Verifique a conexão e tente novamente."
                }
                if (status == 206) {
                    val contentRange = conn.getHeaderField("Content-Range") ?: ""
                    require(contentRange.startsWith("bytes $existing-")) {
                        "O servidor não confirmou o ponto de retomada do download."
                    }
                }
                val append = status == 206 && existing > 0
                var downloaded = if (append) existing else 0L
                val length = conn.contentLengthLong
                val total = if (length > 0) downloaded + length else APPROX_BYTES
                progress(downloaded, total)
                conn.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var lastUi = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 180) {
                                progress(downloaded, total)
                                lastUi = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                progress(downloaded, total)
            } finally {
                conn.disconnect()
            }

            // Verification is intentionally performed only after the download.
            // The already-verified file is reused on subsequent launches.
            if (!checkSha(partial)) {
                partial.delete()
                error("O arquivo baixado não passou na verificação SHA-256. Tente novamente.")
            }
            if (file.exists() && !file.delete()) error("Não foi possível substituir o modelo inválido.")
            check(partial.renameTo(file)) { "Não foi possível finalizar o download." }
            prefs.edit().putString("verified_sha", SHA256).apply()
            file
        }

    private fun checkSha(fileToCheck: File): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        fileToCheck.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                md.update(buffer, 0, count)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual.equals(SHA256, ignoreCase = true)
    }
}
