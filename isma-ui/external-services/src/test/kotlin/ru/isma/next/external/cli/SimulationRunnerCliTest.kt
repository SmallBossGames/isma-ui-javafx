package ru.isma.next.external.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.isma.next.external.dtos.RunSimulationParams

class SimulationRunnerCliTest {

    private fun params(
        accuracy: Double = 0.01,
        isAccuracyInUse: Boolean = false,
        isStabilityControlInUse: Boolean = false,
        eventDetectionGamma: Double? = null,
        eventDetectionLowBorder: Double? = null,
    ) = RunSimulationParams(
        startTime = 0.0,
        endTime = 10.0,
        initialStep = 0.1,
        methodName = "euler",
        accuracy = accuracy,
        isAccuracyInUse = isAccuracyInUse,
        isStabilityControlInUse = isStabilityControlInUse,
        compiledModelId = "/tmp/model.class",
        eventDetectionGamma = eventDetectionGamma,
        eventDetectionLowBorder = eventDetectionLowBorder,
    )

    @Test
    fun `buildArguments produces base arguments`() {
        val args = SimulationRunnerCli.buildArguments(params())

        assertEquals(
            listOf("--model", "/tmp/model.class", "--start", "0.0", "--end", "10.0", "--step", "0.1", "--method", "euler"),
            args,
        )
    }

    @Test
    fun `buildArguments adds accuracy flags when in use`() {
        val args = SimulationRunnerCli.buildArguments(params(accuracy = 0.001, isAccuracyInUse = true))

        assertEquals(
            listOf(
                "--model", "/tmp/model.class", "--start", "0.0", "--end", "10.0", "--step", "0.1",
                "--method", "euler", "--accuracy", "0.001", "--accuracy-enabled",
            ),
            args,
        )
    }

    @Test
    fun `buildArguments omits accuracy flags when not in use`() {
        val args = SimulationRunnerCli.buildArguments(params(accuracy = 0.001, isAccuracyInUse = false))

        assertEquals(false, args.contains("--accuracy"))
        assertEquals(false, args.contains("--accuracy-enabled"))
    }

    @Test
    fun `buildArguments adds stability flag when in use`() {
        val args = SimulationRunnerCli.buildArguments(params(isStabilityControlInUse = true))

        assertEquals(true, args.contains("--stability-enabled"))
    }

    @Test
    fun `buildArguments adds event detection flags when configured`() {
        val args = SimulationRunnerCli.buildArguments(
            params(eventDetectionGamma = 0.5, eventDetectionLowBorder = 0.01)
        )

        assertEquals(
            listOf(
                "--model", "/tmp/model.class", "--start", "0.0", "--end", "10.0", "--step", "0.1",
                "--method", "euler", "--gamma", "0.5", "--low-border", "0.01",
            ),
            args,
        )
    }

    @Test
    fun `buildArguments omits event detection flags when not configured`() {
        val args = SimulationRunnerCli.buildArguments(params(eventDetectionGamma = null, eventDetectionLowBorder = null))

        assertEquals(false, args.contains("--gamma"))
        assertEquals(false, args.contains("--low-border"))
    }

    @Test
    fun `formatNumber renders doubles without locale grouping`() {
        assertEquals("0.1", SimulationRunnerCli.formatNumber(0.1))
        assertEquals("10.0", SimulationRunnerCli.formatNumber(10.0))
        assertEquals("1.0E-5", SimulationRunnerCli.formatNumber(1e-5))
    }
}
