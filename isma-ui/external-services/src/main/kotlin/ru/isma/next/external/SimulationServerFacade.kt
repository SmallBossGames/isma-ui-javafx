package ru.isma.next.external

import org.slf4j.LoggerFactory
import ru.isma.next.domain.models.SimulationProgress
import ru.isma.next.external.cli.ActiveSimulation
import ru.isma.next.external.cli.CliWorkDir
import ru.isma.next.external.cli.LismaCompilerCli
import ru.isma.next.external.cli.SimulationRunnerCli
import ru.isma.next.external.dtos.CachedSimulationResult
import ru.isma.next.external.dtos.CompilationOutcome
import ru.isma.next.external.dtos.CompileResult
import ru.isma.next.external.dtos.RunSimulationParams
import ru.isma.next.external.dtos.ValidationResult
import java.io.File
import java.util.UUID

/**
 * High-level facade for simulation operations. Drives the standalone LISMA
 * compiler CLI and simulation runner CLI directly, without the isma-daemon.
 * Model IDs are local paths of compiled `.class` files; simulation IDs are
 * local.
 */
class SimulationServerFacade(
    private val compiler: LismaCompilerCli,
    private val runner: SimulationRunnerCli,
) {
    private val logger = LoggerFactory.getLogger(SimulationServerFacade::class.java)
    private val gate = Any()
    private val simulations = mutableMapOf<Long, SimulationEntry>()
    private val modelFiles = mutableListOf<File>()
    private var nextSimulationId = 1L

    private class SimulationEntry(
        val simulation: ActiveSimulation,
        val startTime: Double,
        val endTime: Double,
    )

    fun compileModel(lismaSourceCode: String): CompileResult {
        val outcome = compiler.compile(lismaSourceCode)
        return when (outcome) {
            is CompilationOutcome.Success -> {
                val classFile = File(
                    CliWorkDir.root,
                    "isma-model-${UUID.randomUUID().toString().replace("-", "")}.class"
                )
                classFile.writeBytes(outcome.compiledModel)
                synchronized(gate) { modelFiles.add(classFile) }
                CompileResult(modelId = classFile.absolutePath, errors = emptyList(), warnings = emptyList())
            }
            is CompilationOutcome.Failed ->
                CompileResult(modelId = "", errors = outcome.errors, warnings = emptyList())
        }
    }

    fun validateModel(lismaSourceCode: String): ValidationResult {
        val errors = compiler.validate(lismaSourceCode)
        return ValidationResult(errors = errors, warnings = emptyList())
    }

    fun runSimulation(params: RunSimulationParams): Long {
        val simulation = runner.start(params)
        val id = synchronized(gate) {
            val next = nextSimulationId++
            simulations[next] = SimulationEntry(simulation, params.startTime, params.endTime)
            next
        }
        logger.debug("Started simulation {}", id)
        return id
    }

    /**
     * Blocks until the simulation stream ends, invoking [onProgress] with
     * coalesced updates — at most one per [MIN_PROGRESS_INTERVAL_NS] or
     * [MIN_PROGRESS_DELTA] of the time span. The runner emits one row per
     * integration step, and reporting every row would flood the UI thread. The
     * final time is always reported. Intended to run on a background thread; the
     * caller owns the thread.
     */
    fun monitorSimulation(simulationId: Long, onProgress: (SimulationProgress) -> Unit) {
        val entry = getEntry(simulationId)
        val span = entry.endTime - entry.startTime
        if (span <= 0) return

        var lastYieldedTime: Double? = null
        var lastYieldedNormalized: Double? = null
        var lastYieldedAt = Long.MIN_VALUE
        var pending = 0.0
        var hasPending = false

        // Blocking pull from the runner stream thread — no coroutine channel, so
        // stream completion can never surface as an exception.
        while (true) {
            val time = entry.simulation.takeTime() ?: break
            pending = time
            hasPending = true
            val normalized = (pending - entry.startTime) / span
            val now = System.nanoTime()
            val due = lastYieldedNormalized == null ||
                kotlin.math.abs(normalized - lastYieldedNormalized!!) >= MIN_PROGRESS_DELTA ||
                now - lastYieldedAt >= MIN_PROGRESS_INTERVAL_NS
            if (!due) continue
            lastYieldedTime = pending
            lastYieldedNormalized = normalized
            lastYieldedAt = now
            onProgress(SimulationProgress(entry.startTime, entry.endTime, pending))
        }

        if (hasPending && lastYieldedTime != pending) {
            // the final step was coalesced away — report it so progress reaches the end
            onProgress(SimulationProgress(entry.startTime, entry.endTime, pending))
        }
    }

    fun downloadResultToCache(simulationId: Long): CachedSimulationResult =
        getEntry(simulationId).simulation.getResult()

    fun cancelSimulation(simulationId: Long) {
        getEntry(simulationId).simulation.cancel()
    }

    fun getSimulationMethods(): List<String> = runner.getMethods()

    fun shutdown() {
        val entries: List<SimulationEntry>
        val files: List<File>
        synchronized(gate) {
            entries = simulations.values.toList()
            simulations.clear()
            files = modelFiles.toList()
            modelFiles.clear()
        }
        entries.forEach { it.simulation.cancel() }
        files.forEach { runCatching { it.delete() } }
        logger.info("Shut down CLI simulation facade")
    }

    private fun getEntry(simulationId: Long): SimulationEntry =
        simulations[simulationId] ?: throw IllegalStateException("Unknown simulation id: $simulationId")

    companion object {
        private const val MIN_PROGRESS_INTERVAL_NS = 100_000_000L // 100 ms in nanoseconds
        private const val MIN_PROGRESS_DELTA = 0.005
    }
}
