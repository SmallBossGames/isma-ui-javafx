package ru.isma.next.external.cli

import org.slf4j.LoggerFactory
import ru.isma.next.external.dtos.RunSimulationParams
import java.util.concurrent.CompletableFuture

/**
 * Adapter for the standalone simulation runner CLI.
 * Protocol: `--model <class> --start --end --step --method` plus optional
 * accuracy/stability/event-detection flags. The runner streams the result to
 * stdout in the ISMA exchange binary format, one row per integration step;
 * exit 0 is success, exit 1 is failure (`simulation-runner: <message>` on
 * stderr). `--list-methods` prints the bundled integration method names,
 * one per line.
 */
class SimulationRunnerCli(private val launcher: CliLauncher) {

    private val logger = LoggerFactory.getLogger(SimulationRunnerCli::class.java)

    /** Starts a simulation run and returns a handle to the running process. */
    fun start(params: RunSimulationParams): ActiveSimulation {
        val arguments = buildArguments(params)
        logger.debug("Launching simulation runner: {}", arguments.joinToString(" "))
        return ActiveSimulation(launcher.start(arguments))
    }

    /** Lists the integration methods bundled with the runner. */
    fun getMethods(): List<String> {
        val process = launcher.start(listOf("--list-methods"))
        val stdoutFuture = CompletableFuture.supplyAsync { process.stdout.readBytes().decodeToString() }
        val exitCode = process.waitExitCode()
        val stdout = stdoutFuture.get()
        if (exitCode != 0) throw CliToolException(TOOL_NAME, exitCode, process.stderr().trim())
        return stdout.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        private const val TOOL_NAME = "simulation-runner-cli"

        /** Builds the runner command-line arguments for a simulation run. */
        internal fun buildArguments(p: RunSimulationParams): List<String> {
            val args = mutableListOf(
                "--model", p.compiledModelId,
                "--start", formatNumber(p.startTime),
                "--end", formatNumber(p.endTime),
                "--step", formatNumber(p.initialStep),
                "--method", p.methodName,
            )
            if (p.isAccuracyInUse) {
                args += "--accuracy"
                args += formatNumber(p.accuracy)
                args += "--accuracy-enabled"
            }
            if (p.isStabilityControlInUse) {
                args += "--stability-enabled"
            }
            if (p.eventDetectionGamma != null && p.eventDetectionLowBorder != null) {
                args += "--gamma"
                args += formatNumber(p.eventDetectionGamma)
                args += "--low-border"
                args += formatNumber(p.eventDetectionLowBorder)
            }
            return args
        }

        internal fun formatNumber(value: Double): String = value.toString()
    }
}
