package com.example.llama

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Import a user-selected image into the app's private storage for reliable offline history. */
class ImageAttachmentStore(private val context: Context) {
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val limits = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = context.contentResolver.openInputStream(uri)
            ?: error("Não foi possível abrir a imagem.")
        probe.use { BitmapFactory.decodeStream(it, null, limits) }
        require(limits.outWidth > 0 && limits.outHeight > 0) { "Arquivo de imagem inválido." }

        var sample = 1
        while (limits.outWidth / sample > 1600 || limits.outHeight / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        } ?: error("Não foi possível decodificar a imagem.")
        try {
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val dest = File(dir, "${UUID.randomUUID()}.png")
            try {
                dest.outputStream().buffered().use { sink ->
                    check(decoded.compress(Bitmap.CompressFormat.PNG, 100, sink)) {
                        "Falha ao salvar imagem"
                    }
                }
                dest
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
        } finally {
            decoded.recycle()
        }
    }

    companion object {
        private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        fun thumbnail(path: String?, target: Int = 420): Bitmap? {
            if (path.isNullOrBlank() || !File(path).isFile) return null
            val key = "$path:$target"
            cache.get(key)?.let { return it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) sample *= 2
            val result = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (result != null) cache.put(key, result)
            return result
        }
    }
}
