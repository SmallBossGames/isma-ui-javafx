package ru.isma.next.external.cli

import org.slf4j.LoggerFactory
import ru.isma.next.external.dtos.CompilationErrorDto
import ru.isma.next.external.dtos.CompilationOutcome
import java.util.concurrent.CompletableFuture

/**
 * Adapter for the standalone LISMA compiler CLI.
 * Protocol: LISMA source on stdin. Compile mode — exit 0 puts the compiled
 * `.class` bytes on stdout, exit 1 puts `row:col:message` error lines on
 * stdout. Validate mode (`--validate`) — exit 0 is clean, exit 1 puts error
 * lines on stdout. Any other exit code is a tool failure.
 */
class LismaCompilerCli(private val launcher: CliLauncher) {

    private val logger = LoggerFactory.getLogger(LismaCompilerCli::class.java)

    /** Compiles LISMA source. Returns the compiled model bytes or the diagnostics. */
    fun compile(source: String): CompilationOutcome {
        logger.debug("Launching LISMA compiler: compile")
        val process = launcher.start(emptyList())
        val (exitCode, stdout) = runWithSource(process, source)
        return when (exitCode) {
            0 -> CompilationOutcome.Success(stdout)
            1 -> CompilationOutcome.Failed(parseErrorLines(stdout))
            else -> throw toolFailure(process, exitCode)
        }
    }

    /** Validates LISMA source without producing a compiled model. */
    fun validate(source: String): List<CompilationErrorDto> {
        logger.debug("Launching LISMA compiler: --validate")
        val process = launcher.start(listOf("--validate"))
        val (exitCode, stdout) = runWithSource(process, source)
        return when (exitCode) {
            0 -> emptyList()
            1 -> parseErrorLines(stdout)
            else -> throw toolFailure(process, exitCode)
        }
    }

    private fun runWithSource(process: CliProcess, source: String): Pair<Int, ByteArray> {
        // start draining stdout before writing stdin so a chatty tool cannot deadlock the pipe
        val stdoutFuture = CompletableFuture.supplyAsync { process.stdout.readBytes() }
        process.stdin.use { it.write(source.toByteArray(Charsets.UTF_8)) }
        val stdout = stdoutFuture.get()
        val exitCode = process.waitExitCode()
        return exitCode to stdout
    }

    private fun toolFailure(process: CliProcess, exitCode: Int): CliToolException {
        val stderr = process.stderr().trim()
        return CliToolException(TOOL_NAME, exitCode, stderr)
    }

    private fun parseErrorLines(stdout: ByteArray): List<CompilationErrorDto> {
        val text = stdout.decodeToString()
        return text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseErrorLine(it) }
    }

    companion object {
        private const val TOOL_NAME = "lisma-compiler-cli"

        /**
         * Parses a `<row>:<col>:<message>` diagnostic line.
         * Row/col of -1 mean the error has no source position.
         */
        internal fun parseErrorLine(line: String): CompilationErrorDto {
            val first = line.indexOf(':')
            if (first <= 0) return CompilationErrorDto(-1, -1, line)

            val rest = line.substring(first + 1)
            val second = rest.indexOf(':')
            if (second <= 0) return CompilationErrorDto(parsePosition(line.substring(0, first)), -1, rest)

            return CompilationErrorDto(
                row = parsePosition(line.substring(0, first)),
                column = parsePosition(rest.substring(0, second)),
                message = rest.substring(second + 1),
            )
        }

        private fun parsePosition(value: String): Int = value.toIntOrNull() ?: -1
    }
}
