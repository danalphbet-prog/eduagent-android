package com.example.llama

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Two-file GGUF vision model. Neither file is downloaded a second time after verification. */
class VisionModelRepository(private val context: Context) {
    companion object {
        const val NAME = "SmolVLM 500M · Q4_K_M"
        const val TOTAL_DOWNLOAD_MB = 502

        private const val BASE = "https://huggingface.co/pierretokns/SmolVLM-500M-Instruct-GGUF/resolve/main/"
        private val WEIGHTS = ModelFile(
            "SmolVLM-500M-Instruct-Q4_K_M.gguf",
            "0036ee911a27da7d039e084f2d2c32b9976c81d9dde8945811ed589a3099bca8",
            303_000_000L
        )
        private val PROJECTOR = ModelFile(
            "mmproj-SmolVLM-500M-Instruct-f16.gguf",
            "a361f1d5248b7569e3ad8703b8f42b18937a60d8871baf876dda182d29ec1a72",
            199_000_000L
        )
    }

    data class Installed(val weights: File, val projector: File)

    private data class ModelFile(val name: String, val sha256: String, val approximateBytes: Long)

    private val folder: File get() = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("vision_models_v1", Context.MODE_PRIVATE)

    suspend fun installedOrNull(): Installed? = withContext(Dispatchers.IO) {
        val weights = verified(WEIGHTS) ?: return@withContext null
        val projector = verified(PROJECTOR) ?: return@withContext null
        Installed(weights, projector)
    }

    fun downloadedBytes(): Long = listOf(WEIGHTS, PROJECTOR).sumOf {
        val file = File(folder, it.name)
        if (file.exists()) file.length() else 0L
    }

    suspend fun install(onProgress: (label: String, bytes: Long, total: Long) -> Unit): Installed =
        withContext(Dispatchers.IO) {
            val remaining = listOf(WEIGHTS, PROJECTOR).sumOf { spec ->
                val done = File(folder, spec.name).takeIf { it.exists() }?.length() ?: 0L
                val partial = File(folder, "${spec.name}.part").takeIf { it.exists() }?.length() ?: 0L
                (spec.approximateBytes - done - partial).coerceAtLeast(0L)
            }
            require(folder.usableSpace >= remaining + 100_000_000L) {
                "Espaço insuficiente: deixe aproximadamente 650 MB livres para o modelo visual."
            }
            val total = WEIGHTS.approximateBytes + PROJECTOR.approximateBytes
            val weights = downloadOne(WEIGHTS) { current ->
                onProgress("Modelo visual (Q4)", current, total)
            }
            val projector = downloadOne(PROJECTOR) { current ->
                onProgress("Projetor visual", WEIGHTS.approximateBytes + current, total)
            }
            Installed(weights, projector)
        }

    suspend fun remove() = withContext(Dispatchers.IO) {
        for (spec in listOf(WEIGHTS, PROJECTOR)) {
            File(folder, spec.name).let { if (it.exists()) check(it.delete()) { "Falha ao remover ${it.name}" } }
            File(folder, "${spec.name}.part").delete()
        }
        prefs.edit().clear().apply()
        Unit
    }

    private fun verified(spec: ModelFile): File? {
        val dest = File(folder, spec.name)
        if (!dest.isFile || dest.length() < spec.approximateBytes * 90 / 100) return null
        val key = "verified_${spec.name}"
        if (prefs.getString(key, null) == spec.sha256) return dest
        return if (checkSha(dest, spec.sha256)) {
            prefs.edit().putString(key, spec.sha256).apply()
            dest
        } else null
    }

    private fun downloadOne(spec: ModelFile, progress: (Long) -> Unit): File {
        verified(spec)?.let { progress(spec.approximateBytes); return it }
        val dest = File(folder, spec.name)
        val tmp = File(folder, "${spec.name}.part")
        val existing = if (tmp.exists()) tmp.length() else 0L
        val connection = (URL(BASE + spec.name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "EduAgent-Android/0.3")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = connection.responseCode
            require(code == 200 || code == 206) { "Download indisponível (HTTP $code)" }
            if (code == 206) {
                require(connection.getHeaderField("Content-Range")?.startsWith("bytes $existing-") == true) {
                    "Servidor não confirmou a retomada do download."
                }
            }
            val append = code == 206 && existing > 0
            var received = if (append) existing else 0L
            progress(received)
            connection.inputStream.use { source ->
                FileOutputStream(tmp, append).use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    var lastNotify = 0L
                    while (true) {
                        val size = source.read(buffer)
                        if (size < 0) break
                        sink.write(buffer, 0, size)
                        received += size
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 200) {
                            progress(received)
                            lastNotify = now
                        }
                    }
                    sink.fd.sync()
                }
            }
            progress(received)
        } finally {
            connection.disconnect()
        }

        // Never trust a partial or corrupt file, even when its size looks correct.
        if (!checkSha(tmp, spec.sha256)) {
            tmp.delete()
            error("SHA-256 inválido para ${spec.name}. Tente baixar novamente.")
        }
        if (dest.exists()) check(dest.delete()) { "Não foi possível substituir ${spec.name}." }
        check(tmp.renameTo(dest)) { "Não foi possível salvar ${spec.name}." }
        prefs.edit().putString("verified_${spec.name}", spec.sha256).apply()
        return dest
    }

    private fun checkSha(file: File, expected: String): Boolean {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected
    }
}
