package ru.isma.next.external.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.isma.next.external.dtos.CompilationErrorDto

class LismaCompilerCliTest {

    @Test
    fun `parseErrorLine parses row col message`() {
        val error = LismaCompilerCli.parseErrorLine("3:7:Unexpected token")

        assertEquals(3, error.row)
        assertEquals(7, error.column)
        assertEquals("Unexpected token", error.message)
    }

    @Test
    fun `parseErrorLine keeps colons inside the message`() {
        val error = LismaCompilerCli.parseErrorLine("1:2:Expected ';', found ':'")

        assertEquals(1, error.row)
        assertEquals(2, error.column)
        assertEquals("Expected ';', found ':'", error.message)
    }

    @Test
    fun `parseErrorLine without column falls back to -1`() {
        val error = LismaCompilerCli.parseErrorLine("5:Model is empty")

        assertEquals(5, error.row)
        assertEquals(-1, error.column)
        assertEquals("Model is empty", error.message)
    }

    @Test
    fun `parseErrorLine without position falls back to -1 -1`() {
        val error = LismaCompilerCli.parseErrorLine("Internal compiler failure")

        assertEquals(CompilationErrorDto(-1, -1, "Internal compiler failure"), error)
    }

    @Test
    fun `parseErrorLine with non-numeric position falls back to -1`() {
        val error = LismaCompilerCli.parseErrorLine("a:b:message")

        assertEquals(-1, error.row)
        assertEquals(-1, error.column)
        assertEquals("message", error.message)
    }
}
