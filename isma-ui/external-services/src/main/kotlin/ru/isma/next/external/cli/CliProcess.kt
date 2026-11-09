package ru.isma.next.external.cli

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A launched CLI tool process: access to its stdin/stdout/stderr, its exit code,
 * and graceful termination (SIGTERM on Unix, hard kill as a fallback).
 */
class CliProcess(private val process: Process) {

    private val stderrFuture: CompletableFuture<String> =
        CompletableFuture.supplyAsync { process.errorStream.readBytes().decodeToString() }

    val id: Long get() = process.pid()

    /** The process stdin. Write the input, then close it. */
    val stdin: OutputStream get() = process.outputStream

    /** The process stdout — the tool's output stream. */
    val stdout: InputStream get() = process.inputStream

    val hasExited: Boolean get() = !process.isAlive

    /** Waits for the process to exit and returns its exit code. */
    fun waitExitCode(): Int {
        process.waitFor()
        return process.exitValue()
    }

    /** The accumulated stderr; available once the process has exited. */
    fun stderr(): String = stderrFuture.get()

    /**
     * Terminates the process: SIGTERM on Unix (so the tool can run its shutdown hooks),
     * a hard kill if it does not exit in time; a hard kill on Windows.
     */
    fun terminate() {
        if (!process.isAlive) return

        if (!IS_WINDOWS) {
            trySendSigTerm()
            if (process.waitFor(GRACEFUL_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return
        }
        process.destroyForcibly()
    }

    private fun trySendSigTerm() {
        try {
            ProcessBuilder("kill", "-TERM", id.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        } catch (e: Exception) {
            // best effort — fall through to a hard kill
        }
    }

    companion object {
        private const val GRACEFUL_EXIT_TIMEOUT_SECONDS = 2L
        private val IS_WINDOWS = System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
