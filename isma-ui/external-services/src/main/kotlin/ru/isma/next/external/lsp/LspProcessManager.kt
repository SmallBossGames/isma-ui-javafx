package ru.isma.next.external.lsp

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Manages the LISMA language server process, mirroring
 * [ru.isma.next.external.SimulationServerManager] for the gRPC server.
 *
 * The script path is resolved from the `ISMA_LSP_SCRIPT` environment
 * variable or the `isma.lsp.script` system property.
 */
class LspProcessManager(
    private val scriptPath: String = resolveLspScriptPath(),
) {
    companion object {
        const val ENV_VAR = "ISMA_LSP_SCRIPT"
        const val PROP_NAME = "isma.lsp.script"

        private fun resolveLspScriptPath(): String {
            return System.getenv(ENV_VAR)
                ?: System.getProperty(PROP_NAME)
                ?: throw IllegalStateException(
                    "Neither environment variable '$ENV_VAR' nor system property '$PROP_NAME' is set. " +
                        "One of them is required to point to the isma-lsp launch script."
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LspProcessManager::class.java)
    private val runningLock = Any()
    private var transport: LspTransport? = null
    @Volatile
    private var running = false

    /** Starts the language server process if needed and returns its transport. */
    fun start(): LspTransport {
        if (running) return transport!!

        val file = File(scriptPath)
        require(file.exists()) { "isma-lsp script not found at: $scriptPath" }

        val newTransport = ProcessLspTransport(scriptPath)
        synchronized(runningLock) {
            if (running) return transport!!
            transport = newTransport
            running = true
        }
        logger.info("isma-lsp started from: $scriptPath")
        return newTransport
    }

    fun stop() {
        val toClose: LspTransport?
        synchronized(runningLock) {
            if (!running) return
            running = false
            toClose = transport
            transport = null
        }
        toClose?.close()
        logger.info("isma-lsp stopped")
    }
}
