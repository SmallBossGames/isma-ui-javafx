package ru.isma.next.app.services.simulation

import ru.isma.next.app.models.LismaTextModel
import ru.isma.next.app.models.simulation.SimulationParametersModel
import ru.isma.next.app.models.simulation.SimulationTask

interface ISimulationTaskService : AutoCloseable {

    val tasks: List<SimulationTask>

    /**
     * Registers a listener invoked on the UI thread whenever the task list
     * changes (a task is added, removed, or its status/progress updates).
     * Returns a handle whose [close] removes the listener.
     */
    fun addTaskListener(listener: () -> Unit): AutoCloseable

    fun submit(
        modelName: String,
        lisma: LismaTextModel,
        simulationParameters: SimulationParametersModel,
    ): SimulationTask

    fun cancelTask(task: SimulationTask)

    fun removeTask(task: SimulationTask)
}
