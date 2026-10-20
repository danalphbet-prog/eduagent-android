package com.example.llama

import org.junit.Assert.*
import org.junit.Test

class CodeBlocksTest {
    @Test fun recognizesMultipleFences() {
        val source = """
            Um exemplo:
            ```javascript
            console.log('oi')
            ```
            E outro:
            ```html
            <h1>Olá</h1>
            ```
        """.trimIndent()
        val found = CodeBlocks.parse(source)
        assertEquals(2, found.size)
        assertEquals("javascript", found[0].language)
        assertEquals("main.js", found[0].suggestedFilename)
        assertEquals("<h1>Olá</h1>", found[1].source)
    }

    @Test fun rejectsIncompleteOrHugeFences() {
        assertTrue(CodeBlocks.parse("```python\nprint(1)").isEmpty())
        assertTrue(CodeBlocks.parse("```txt\n${"a".repeat(120_001)}\n```").isEmpty())
    }

    @Test fun detectsStructuredOperations() {
        assertTrue(CodeBlock("setData", "{}", 0).isSetData)
        assertTrue(CodeBlock("bash", "pwd", 1).isShell)
    }
}
