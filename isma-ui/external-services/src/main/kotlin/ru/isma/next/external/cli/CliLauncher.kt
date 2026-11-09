package ru.isma.next.external.cli

/**
 * Resolves the path of one configured CLI tool and launches it.
 * Path resolution order: environment variable, then the system property.
 */
class CliLauncher(
    private val toolName: String,
    private val envVarName: String,
    private val sysPropName: String,
) {
    /**
     * Resolves the tool executable path.
     * Throws [IllegalStateException] when the tool is not configured.
     */
    fun resolvePath(): String {
        System.getenv(envVarName)?.takeIf { it.isNotBlank() }?.let { return it }
        System.getProperty(sysPropName)?.takeIf { it.isNotBlank() }?.let { return it }
        throw IllegalStateException(
            "Cannot resolve $toolName path. Set the $envVarName environment variable or the '$sysPropName' system property."
        )
    }

    /** Launches the tool with the given arguments and wraps the process. */
    fun start(arguments: List<String>): CliProcess {
        val process = ProcessBuilder(listOf(resolvePath()) + arguments).start()
        return CliProcess(process)
    }
}
