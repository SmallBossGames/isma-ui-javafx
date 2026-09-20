package ru.isma.next.external.lsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal LSP client for the LISMA language server.
 *
 * Speaks JSON-RPC 2.0 over an [LspTransport]: performs the
 * initialize/initialized handshake lazily, keeps full-document state on the
 * server side (didOpen/didChange), and exposes pull-model semantic tokens.
 *
 * All suspend entry points must be called from a single dispatcher (the
 * JavaFX thread in the app); [shutdown] is the only blocking entry point.
 */
class LspClient(
    private val transport: LspTransport,
) {
    /** A decoded semantic token with line/column offsets. */
    data class Token(
        val line: Int,
        val startChar: Int,
        val length: Int,
        val type: String,
    )

    /** Raised when the server responds to a request with an error object. */
    class LspRequestException(message: String) : RuntimeException(message)

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val nextId = AtomicLong(1)
    private val openedDocuments = ConcurrentHashMap.newKeySet<String>()
    private val documentVersions = ConcurrentHashMap<String, Int>()
    private val initialized = AtomicReference<Boolean>(false)
    private val legend = AtomicReference<List<String>>(emptyList())
    @Volatile
    private var closed = false

    private val readerThread: Thread = Thread({ readerLoop() }, "lsp-client-reader").apply {
        isDaemon = true
        start()
    }

    /**
     * Requests full-document semantic tokens, opening or updating the
     * document on the server first.
     */
    suspend fun semanticTokens(documentId: String, source: String): List<Token> {
        ensureInitialized()
        val uri = documentUri(documentId)
        if (openedDocuments.add(uri)) {
            sendNotification(
                "textDocument/didOpen",
                buildJsonObject {
                    putJsonObject("textDocument") {
                        put("uri", uri)
                        put("languageId", LANGUAGE_ID)
                        put("version", 1)
                        put("text", source)
                    }
                },
            )
        } else {
            val version = (documentVersions[uri] ?: 1) + 1
            documentVersions[uri] = version
            sendNotification(
                "textDocument/didChange",
                buildJsonObject {
                    putJsonObject("textDocument") {
                        put("uri", uri)
                        put("version", version)
                    }
                    put(
                        "contentChanges",
                        buildJsonArray {
                            add(buildJsonObject { put("text", source) })
                        },
                    )
                },
            )
        }

        val result = request(
            "textDocument/semanticTokens/full",
            buildJsonObject {
                putJsonObject("textDocument") { put("uri", uri) }
            },
        )
        val data = result.jsonObject["data"]?.jsonArray ?: return emptyList()
        return decodeTokens(data)
    }

    /** Notifies the server that the document was closed. */
    fun closeDocument(documentId: String) {
        val uri = documentUri(documentId)
        if (!openedDocuments.remove(uri)) return
        documentVersions.remove(uri)
        sendNotification(
            "textDocument/didClose",
            buildJsonObject {
                putJsonObject("textDocument") { put("uri", uri) }
            },
        )
    }

    /**
     * Best-effort graceful shutdown: shutdown request, exit notification,
     * transport close. Never throws.
     */
    fun shutdown() {
        if (closed) return
        closed = true
        try {
            if (initialized.get()) {
                try {
                    runBlocking { request("shutdown", buildJsonObject {}) }
                    sendNotification("exit", buildJsonObject {})
                } catch (e: Exception) {
                    // Server already gone
                }
            }
        } finally {
            transport.close()
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized.get()) return
        val result = request(
            "initialize",
            buildJsonObject {
                put("processId", JsonNull)
                put("rootUri", JsonNull)
                put("capabilities", buildJsonObject {})
                putJsonObject("clientInfo") {
                    put("name", "isma-ui")
                    put("version", "1.0")
                }
            },
        )
        legend.set(
            result.jsonObject["capabilities"]?.jsonObject
                ?.get("semanticTokensProvider")?.jsonObject
                ?.get("legend")?.jsonObject
                ?.get("tokenTypes")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList(),
        )
        sendNotification("initialized", buildJsonObject {})
        initialized.set(true)
    }

    private suspend fun request(method: String, params: JsonObject): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        sendRequest(id, method, params)
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun sendRequest(id: Long, method: String, params: JsonObject) {
        transport.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString(),
        )
    }

    private fun sendNotification(method: String, params: JsonObject) {
        transport.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString(),
        )
    }

    private fun readerLoop() {
        while (!closed) {
            val json = try {
                transport.readNext()
            } catch (e: Exception) {
                break
            }
            if (json == null) break
            val message = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                continue
            }
            val id = message["id"]?.jsonPrimitive?.longOrNull ?: continue
            val deferred = pending.remove(id) ?: continue
            val error = message["error"]
            if (error != null) {
                deferred.completeExceptionally(LspRequestException(error.toString()))
            } else {
                deferred.complete(message["result"] ?: JsonNull)
            }
        }
    }

    private fun decodeTokens(data: JsonArray): List<Token> {
        val types = legend.get()
        val values = data.map { it.jsonPrimitive.long }
        val tokens = ArrayList<Token>(values.size / 5)
        var line = 0
        var start = 0
        var i = 0
        while (i + 4 < values.size) {
            val deltaLine = values[i]
            val deltaStart = values[i + 1]
            val length = values[i + 2]
            val typeIndex = values[i + 3].toInt()
            if (deltaLine == 0L) {
                start += deltaStart.toInt()
            } else {
                line += deltaLine.toInt()
                start = deltaStart.toInt()
            }
            tokens.add(Token(line, start, length.toInt(), types.getOrNull(typeIndex) ?: "unknown"))
            i += 5
        }
        return tokens
    }

    private fun documentUri(documentId: String) = "file:///isma/$documentId.lisma"

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val LANGUAGE_ID = "lisma"
    }
}
