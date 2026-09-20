package ru.isma.next.external.lsp

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * [LspTransport] backed by a child process speaking LSP over stdio.
 *
 * stdout carries the protocol (Content-Length framing); stderr is drained
 * and logged so it cannot corrupt the protocol stream.
 */
class ProcessLspTransport(
    scriptPath: String,
) : LspTransport {
    private val logger = LoggerFactory.getLogger(ProcessLspTransport::class.java)

    private val process: Process = ProcessBuilder(scriptPath)
        .redirectErrorStream(false)
        .start()

    private val output: OutputStream = process.outputStream
    private val input: InputStream = process.inputStream

    init {
        Thread({ drainStderr() }, "lsp-stderr").apply {
            isDaemon = true
            start()
        }
    }

    override fun send(json: String) {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    override fun readNext(): String? {
        val header = readUntil(input, HEADER_DELIMITER) ?: return null
        val contentLength = header.lineSequence()
            .firstOrNull { it.startsWith(CONTENT_LENGTH_PREFIX) }
            ?.substringAfter(CONTENT_LENGTH_PREFIX)
            ?.trim()
            ?.toIntOrNull()
            ?: throw IllegalStateException("Malformed LSP header: $header")
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read < 0) return null
            offset += read
        }
        return String(body, StandardCharsets.UTF_8)
    }

    override fun close() {
        try {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    private fun drainStderr() {
        try {
            process.errorStream.use { stream ->
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { logger.debug("[lsp] {}", it) }
                }
            }
        } catch (e: Exception) {
            // Stream closed on process exit
        }
    }

    private fun readUntil(stream: InputStream, delimiter: String): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b < 0) return if (buffer.isEmpty()) null else buffer.toString()
            buffer.append(b.toChar())
            if (buffer.length >= delimiter.length &&
                buffer.substring(buffer.length - delimiter.length) == delimiter
            ) {
                return buffer.toString()
            }
        }
    }

    private companion object {
        const val HEADER_DELIMITER = "\r\n\r\n"
        const val CONTENT_LENGTH_PREFIX = "Content-Length:"
    }
}
