package ru.isma.next.external.cli

import java.io.File

/**
 * The shared work directory for CLI tool artifacts: compiled `.class` models
 * and simulation result files. Lives in the system temp directory.
 */
internal object CliWorkDir {
    val root: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "isma-ui")
        dir.mkdirs()
        dir
    }
}
