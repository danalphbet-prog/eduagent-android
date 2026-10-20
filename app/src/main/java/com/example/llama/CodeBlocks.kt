package com.example.llama

/** Renderable code/text blocks; model output is always untrusted. */
data class CodeBlock(val language: String, val source: String, val position: Int) {
    val isSetData: Boolean get() = language.equals("setdata", true)
    val isShell: Boolean get() = language.lowercase() in setOf("bash", "sh", "shell", "terminal")

    val suggestedFilename: String get() = when (language.lowercase()) {
        "html" -> "index.html"
        "javascript", "js" -> "main.js"
        "typescript", "ts" -> "main.ts"
        "python", "py" -> "main.py"
        "kotlin", "kt" -> "Main.kt"
        "java" -> "Main.java"
        "json" -> "data.json"
        "css" -> "style.css"
        "xml" -> "document.xml"
        "bash", "sh", "shell", "terminal" -> "script.sh"
        else -> "codigo.txt"
    }
}

object CodeBlocks {
    // Strict completed fences: unfinished streaming output is kept as plain text.
    private val fence = Regex("(?m)^```([a-zA-Z0-9_.+#-]{0,24})[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*$" )

    fun parse(text: String): List<CodeBlock> = fence.findAll(text)
        .take(12)
        .mapIndexedNotNull { index, match ->
            val value = match.groupValues[2]
            if (value.isBlank() || value.length > 120_000) null
            else CodeBlock(match.groupValues[1].ifBlank { "txt" }, value, index)
        }.toList()

    fun prose(text: String): String = fence.replace(text, "").trim()
}

enum class CodeAction { COPY, OPEN, DOWNLOAD, SAVE, RUN }
