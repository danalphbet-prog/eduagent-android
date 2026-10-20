package com.example.llama

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A visible, user-approved code workspace. Never directly execute model output. */
class WorkspaceTools(private val context: Context) {
    val root: File = File(context.filesDir, "code_workspace").apply { mkdirs() }.canonicalFile

    fun safeFile(path: String): File {
        require(path.length in 1..140 && !path.startsWith('/') && '\\' !in path) { "Nome de arquivo inválido" }
        require(path.split('/').none { it == ".." || it.isEmpty() }) { "O caminho não pode sair do workspace" }
        require(path.matches(Regex("[A-Za-z0-9_./ -]+"))) { "Use apenas letras, números, espaços, pontos e traços" }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Caminho fora do workspace" }
        return file
    }

    fun setData(path: String, contents: String): File {
        require(contents.toByteArray(Charsets.UTF_8).size <= 512_000) { "Limite de 500 KB por arquivo" }
        val target = safeFile(path)
        target.parentFile?.mkdirs()
        require(!target.isDirectory) { "O destino é uma pasta" }
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { stream ->
                stream.write(contents.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
            return target
        } finally {
            temporary.delete()
        }
    }

    fun relativeFiles(): List<String> = root.walkTopDown()
        .filter { it.isFile && !it.name.startsWith(".terminal-output-") }
        .take(200)
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toList()

    /** Only used when the person explicitly writes @workspace in a message. */
    fun previewForAi(): String {
        val files = relativeFiles()
        if (files.isEmpty()) return "Workspace vazio. Salve algum código antes."
        return buildString {
            appendLine("Arquivos: ${files.take(30).joinToString(", ")}")
            files.take(4).forEach { path ->
                val file = safeFile(path)
                if (file.isFile && file.length() in 1L..100_000L) {
                    appendLine("\nARQUIVO $path (trecho, pode estar incompleto):")
                    file.bufferedReader().use { reader ->
                        val data = CharArray(650)
                        val count = reader.read(data)
                        if (count > 0) appendLine(String(data, 0, count))
                    }
                }
            }
        }.take(3_300)
    }

    fun saveToDownloads(fileName: String, content: String): String {
        val simple = fileName.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            .ifBlank { "codigo.txt" }
        require(content.length <= 500_000) { "Arquivo muito grande para exportação" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, simple)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EduAgent")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Não foi possível criar o arquivo no Downloads")
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Não foi possível abrir o arquivo exportado")
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            return simple
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    data class CommandResult(val exitCode: Int?, val output: String, val timedOut: Boolean)

    /** Android's real sh as the regular app UID, not Termux, root, or host Linux. */
    suspend fun runApproved(command: String): CommandResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank() && command.length <= 4_000) { "Comando vazio ou muito longo" }
        val outputFile = File(root, ".terminal-output-${UUID.randomUUID()}")
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(root)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .apply {
                environment()["HOME"] = root.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
        try {
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = outputFile.inputStream().bufferedReader().use { reader ->
                val buffer = CharArray(30_000)
                val count = reader.read(buffer).coerceAtLeast(0)
                String(buffer, 0, count)
            }
            CommandResult(if (finished) process.exitValue() else null,
                output.ifBlank { "(sem saída)" }, !finished)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            outputFile.delete()
        }
    }
}
