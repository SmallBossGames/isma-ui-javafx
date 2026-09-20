package ru.isma.next.app.services.editors

import javafx.application.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import ru.isma.next.editor.text.IsmaTextEditor
import ru.isma.next.editor.text.services.EditorPlatformService
import ru.isma.next.editor.text.services.RemoteLismaHighlightingService
import ru.isma.next.external.lsp.LspClient
import ru.isma.next.external.lsp.LspTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Headless end-to-end test: a real [IsmaTextEditor] driven through the real
 * [LspClient] over a fake in-memory transport. Asserts that semantic tokens
 * arrive from the LSP layer and are applied to the editor as style spans.
 */
class LspHighlightingEditorTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun startToolkit() {
            try {
                Platform.startup { }
            } catch (e: IllegalStateException) {
                // Toolkit already started by another test class
            }
        }
    }

    @Test
    fun `editor applies LSP semantic tokens as style spans`() {
        val source = "const a = 1.5; // note"
        // const -> (0,0,5,keyword), 1.5 -> (0,10,3,number), // note -> (0,14,7,comment)
        // deltaStart of the third token is relative to the previous start on the line (14 - 10 = 4)
        val data = listOf(0L, 0L, 5L, 0L, 0L, 0L, 10L, 3L, 2L, 0L, 0L, 4L, 7L, 1L, 0L)
        val transport = FakeLspTransport(source, data)
        val client = LspClient(transport)
        val service = RemoteLismaHighlightingService(LspSyntaxHighlighter(client))
        val editor = onFx { IsmaTextEditor(EditorPlatformService(), service) }
        val area = onFx { editor.center as CodeArea }
        try {
            onFx { editor.replaceText(source) }

            awaitStyleAt(area, 0, "syntax-keyword")
            awaitStyleAt(area, 10, "syntax-decimal")
            awaitStyleAt(area, 14, "syntax-comment")
            awaitStyleAt(area, 5, "syntax-default")
        } finally {
            onFx { editor.dispose() }
            client.shutdown()
        }
    }

    private fun awaitStyleAt(area: CodeArea, offset: Int, expectedClass: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val style = onFx {
                try {
                    area.getStyleSpans(offset, offset + 1).getStyleSpan(0).style
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (style.contains(expectedClass)) return
            Thread.sleep(50)
        }
        error("Timed out waiting for style class '$expectedClass' at offset $offset")
    }

    private fun <T : Any> onFx(block: () -> T?): T {
        val future = CompletableFuture<T?>()
        Platform.runLater { future.complete(block()) }
        return future.get(15, TimeUnit.SECONDS) ?: error("FX operation returned null")
    }
}

/**
 * In-memory [LspTransport] emulating the LISMA language server. Tracks the
 * document text from didOpen/didChange and answers semanticTokens/full with
 * [semanticTokensData] only while the tracked text matches [expectedSource].
 */
private class FakeLspTransport(
    private val expectedSource: String,
    private val semanticTokensData: List<Long>,
) : LspTransport {
    private val serverToClient = LinkedBlockingQueue<String>()
    @Volatile
    private var documentText: String? = null
    @Volatile
    var closed = false
        private set

    override fun send(json: String) {
        val message = Json.parseToJsonElement(json).jsonObject
        val method = message["method"]?.jsonPrimitive?.content
        when (method) {
            "textDocument/didOpen" -> {
                documentText = message["params"]!!.jsonObject["textDocument"]!!
                    .jsonObject["text"]!!.jsonPrimitive.content
            }
            "textDocument/didChange" -> {
                val changes = message["params"]!!.jsonObject["contentChanges"]!!.jsonArray
                documentText = changes.last().jsonObject["text"]!!.jsonPrimitive.content
            }
        }
        val id = message["id"]?.jsonPrimitive?.long ?: return
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
            "textDocument/semanticTokens/full" -> {
                val data = if (documentText == expectedSource) semanticTokensData else emptyList()
                buildJsonObject {
                    put("data", buildJsonArray { data.forEach { add(it) } })
                }
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
}
