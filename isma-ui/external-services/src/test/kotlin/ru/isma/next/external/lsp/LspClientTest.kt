package ru.isma.next.external.lsp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * In-memory [LspTransport] emulating the LISMA language server: answers
 * initialize/semanticTokens/full/shutdown and records client messages.
 */
private class FakeLspTransport(
    private val semanticTokensData: List<Long>,
) : LspTransport {
    private val serverToClient = LinkedBlockingQueue<String>()
    val received = LinkedBlockingQueue<JsonObject>()
    @Volatile
    var closed = false
        private set

    override fun send(json: String) {
        val message = Json.parseToJsonElement(json).jsonObject
        received.add(message)
        val id = message["id"]?.jsonPrimitive?.long ?: return
        val method = message["method"]?.jsonPrimitive?.content
        val result: JsonElement = when (method) {
            "initialize" -> buildJsonObject {
                putJsonObject("capabilities") {
                    put("textDocumentSync", 1)
                    putJsonObject("semanticTokensProvider") {
                        putJsonObject("legend") {
                            putJsonArray("tokenTypes") {
                                add("keyword")
                                add("comment")
                                add("number")
                            }
                        }
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", "lisma-lsp")
                    put("version", "1.0.0")
                }
            }
            "textDocument/semanticTokens/full" -> buildJsonObject {
                put("data", buildJsonArray { semanticTokensData.forEach { add(it) } })
            }
            else -> JsonNull
        }
        serverToClient.add(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString(),
        )
    }

    override fun readNext(): String? {
        while (true) {
            if (closed) return null
            val next = serverToClient.poll(200, TimeUnit.MILLISECONDS)
            if (next != null) return next
        }
    }

    override fun close() {
        closed = true
    }

    fun methods(): List<String> =
        received.mapNotNull { it["method"]?.jsonPrimitive?.content }

    fun textDocumentParams(method: String): JsonObject? =
        received.firstOrNull { it["method"]?.jsonPrimitive?.content == method }
            ?.get("params")?.jsonObject
            ?.get("textDocument")?.jsonObject
}

class LspClientTest {

    private fun transportWith(data: List<Long>) = FakeLspTransport(data)

    @Test
    fun `semantic tokens are decoded from the wire format`() = runBlocking {
        // Source: "const a = 1.5; // note"
        // const -> (0,0,5,keyword=0), 1.5 -> (0,10,3,number=2), // note -> (0,14,7,comment=1)
        // deltaStart of the third token is relative to the previous start on the line (14 - 10 = 4)
        val data = listOf(0L, 0L, 5L, 0L, 0L, 0L, 10L, 3L, 2L, 0L, 0L, 4L, 7L, 1L, 0L)
        val transport = transportWith(data)
        val client = LspClient(transport)

        val tokens = client.semanticTokens("doc-1", "const a = 1.5; // note")

        assertEquals(
            listOf(
                LspClient.Token(0, 0, 5, "keyword"),
                LspClient.Token(0, 10, 3, "number"),
                LspClient.Token(0, 14, 7, "comment"),
            ),
            tokens,
        )
        client.shutdown()
    }

    @Test
    fun `initialize handshake is sent once before the first request`() = runBlocking {
        val transport = transportWith(emptyList())
        val client = LspClient(transport)

        client.semanticTokens("doc-1", "const a = 1;")
        client.semanticTokens("doc-1", "const a = 2;")

        val methods = transport.methods()
        assertEquals(1, methods.count { it == "initialize" })
        assertTrue(methods.contains("initialized"))
        client.shutdown()
    }

    @Test
    fun `first request opens the document, subsequent requests update it`() = runBlocking {
        val transport = transportWith(emptyList())
        val client = LspClient(transport)

        client.semanticTokens("doc-1", "const a = 1;")
        client.semanticTokens("doc-1", "const a = 2;")

        val open = transport.textDocumentParams("textDocument/didOpen")
        assertEquals("file:///isma/doc-1.lisma", open?.get("uri")?.jsonPrimitive?.content)
        assertEquals("const a = 1;", open?.get("text")?.jsonPrimitive?.content)

        val methods = transport.methods()
        assertEquals(1, methods.count { it == "textDocument/didChange" })
        val change = transport.received.first { it["method"]?.jsonPrimitive?.content == "textDocument/didChange" }
        val changeParams = change["params"]!!.jsonObject
        assertEquals(2, changeParams["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        val changes = changeParams["contentChanges"]!!.jsonArray
        assertEquals("const a = 2;", changes[0].jsonObject["text"]!!.jsonPrimitive.content)
        client.shutdown()
    }

    @Test
    fun `closeDocument notifies the server and is idempotent`() = runBlocking {
        val transport = transportWith(emptyList())
        val client = LspClient(transport)

        client.semanticTokens("doc-1", "const a = 1;")
        client.closeDocument("doc-1")
        client.closeDocument("doc-1")

        val methods = transport.methods()
        assertEquals(1, methods.count { it == "textDocument/didClose" })
        client.shutdown()
    }

    @Test
    fun `different documents are tracked independently`() = runBlocking {
        val transport = transportWith(emptyList())
        val client = LspClient(transport)

        client.semanticTokens("doc-1", "const a = 1;")
        client.semanticTokens("doc-2", "state Main {}")

        val methods = transport.methods()
        assertEquals(2, methods.count { it == "textDocument/didOpen" })
        assertEquals(0, methods.count { it == "textDocument/didChange" })
        client.shutdown()
    }

    @Test
    fun `shutdown sends shutdown request and exit notification`() = runBlocking {
        val transport = transportWith(emptyList())
        val client = LspClient(transport)

        client.semanticTokens("doc-1", "const a = 1;")
        client.shutdown()

        val methods = transport.methods()
        assertTrue(methods.contains("shutdown"))
        assertTrue(methods.contains("exit"))
        assertTrue(transport.closed)
    }
}
