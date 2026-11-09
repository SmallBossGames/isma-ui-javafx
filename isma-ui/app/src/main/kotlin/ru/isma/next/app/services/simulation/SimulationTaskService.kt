package ru.isma.next.app.services.simulation

import ru.isma.javafx.extensions.coroutines.UiThreadExecutor
import ru.isma.next.app.models.CompilationErrorItem
import ru.isma.next.app.models.LismaTextModel
import ru.isma.next.app.models.simulation.CompletedSimulationModel
import ru.isma.next.app.models.simulation.SimulationParametersModel
import ru.isma.next.app.models.simulation.SimulationTask
import ru.isma.next.app.models.simulation.SimulationTaskStatus
import ru.isma.next.app.services.ModelErrorService
import ru.isma.next.external.BinaryEquationIndexProvider
import ru.isma.next.external.SimulationServerFacade
import ru.isma.next.external.dtos.CachedSimulationResult
import ru.isma.next.external.dtos.CompileResult
import ru.isma.next.external.dtos.CompilationErrorDto
import ru.isma.next.external.dtos.RunSimulationParams
import ru.isma.next.domain.models.MetricData
import ru.isma.next.domain.models.SimulationMetadata
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class SimulationTaskService(
    private val serverFacade: SimulationServerFacade,
    private val modelErrorService: ModelErrorService,
    private val uiThreadExecutor: UiThreadExecutor,
) : ISimulationTaskService {

    private val tasksInternal = mutableListOf<SimulationTask>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var nextId = 1L

    // One virtual thread per simulation job: the job blocks on the runner stream,
    // and a virtual thread parks instead of holding a platform thread.
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    override val tasks: List<SimulationTask>
        get() = tasksInternal.toList()

    override fun addTaskListener(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() {
        executor.shutdown()
    }

    override fun submit(
        modelName: String,
        lisma: LismaTextModel,
        simulationParameters: SimulationParametersModel,
    ): SimulationTask {
        val task = SimulationTask(nextId++, modelName, simulationParameters)
        executor.execute { runSimulationJob(task, lisma, simulationParameters) }
        return task
    }

    private fun runSimulationJob(
        task: SimulationTask,
        lisma: LismaTextModel,
        simulationParameters: SimulationParametersModel,
    ) {
        val compileResult = try {
            serverFacade.compileModel(lisma.fullText)
        } catch (e: Exception) {
            fail(task, "Compilation error: ${e.message}")
            return
        } catch (e: Error) {
            throw e
        }

        val errorItems = compileResult.errors.map { error: CompilationErrorDto ->
            CompilationErrorItem(error.row, error.column, lisma.fragmentNameByLine(error.row), error.message)
        }
        modelErrorService.putErrorList(errorItems)

        if (compileResult.errors.isNotEmpty()) {
            fail(task, "Compilation failed: ${compileResult.errors.joinToString("; ") { it.message }}")
            return
        }

        val runParams = simulationParameters.toRunSimulationParams(compileResult.modelId)
        val simulationId = serverFacade.runSimulation(runParams)

        onUi {
            tasksInternal.add(task)
            notifyChange()
        }

        try {
            serverFacade.monitorSimulation(simulationId) { progress ->
                val normalized = ((progress.currentTime - progress.startTime) / (progress.endTime - progress.startTime)).coerceIn(0.0, 1.0)
                onUi {
                    task.progress = normalized
                    notifyChange()
                }
            }
        } catch (e: Exception) {
            fail(task, "Monitor error: ${e.message}")
            return
        } catch (e: Error) {
            throw e
        }

        try {
            val cachedResult: CachedSimulationResult = serverFacade.downloadResultToCache(simulationId)
            val resultModel = CompletedSimulationModel(
                id = task.id.toInt(),
                modelName = task.modelName,
                equationIndexProvider = BinaryEquationIndexProvider(
                    SimulationMetadata(cachedResult.columnNames)
                ),
                metricData = MetricData(),
                parameters = simulationParameters,
                cachedFile = cachedResult.file,
                cachedColumnNames = cachedResult.columnNames,
            )
            onUi {
                task.result = resultModel
                task.status = SimulationTaskStatus.COMPLETED
                task.progress = 1.0
                notifyChange()
            }
        } catch (e: Exception) {
            fail(task, "Download error: ${e.message}")
        } catch (e: Error) {
            throw e
        }
    }

    override fun cancelTask(task: SimulationTask) {
        serverFacade.cancelSimulation(task.id)
    }

    override fun removeTask(task: SimulationTask) {
        tasksInternal.remove(task)
        notifyChange()
    }

    private fun fail(task: SimulationTask, message: String) {
        onUi {
            task.status = SimulationTaskStatus.FAILED
            task.error = message
            notifyChange()
        }
    }

    private fun onUi(block: () -> Unit) {
        uiThreadExecutor.executeOnUi(block)
    }

    private fun notifyChange() {
        listeners.forEach { it() }
    }
}

fun SimulationParametersModel.toRunSimulationParams(compiledModelId: String) = RunSimulationParams(
    startTime = cauchyInitials.startTime,
    endTime = cauchyInitials.endTime,
    initialStep = cauchyInitials.initialStep,
    methodName = integrationMethodParameters.selectedMethod,
    accuracy = integrationMethodParameters.accuracy,
    isAccuracyInUse = integrationMethodParameters.isAccuracyInUse,
    isStabilityControlInUse = integrationMethodParameters.isStableInUse,
    compiledModelId = compiledModelId,
    eventDetectionGamma = if (eventDetectionParameters.isEventDetectionInUse) eventDetectionParameters.gamma else null,
    eventDetectionLowBorder = if (eventDetectionParameters.isEventDetectionInUse) eventDetectionParameters.lowBorder else null,
)
