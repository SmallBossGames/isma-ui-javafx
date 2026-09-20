package ru.isma.next.external.lsp

/**
 * Byte-level transport for the LSP protocol.
 *
 * Implementations deliver complete LSP messages (JSON-RPC 2.0 documents,
 * without Content-Length framing) one at a time. [readNext] blocks until a
 * message is available and returns null on end of stream.
 */
interface LspTransport {
    fun send(json: String)

    fun readNext(): String?

    fun close()
}
