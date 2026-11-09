package ru.isma.next.external.cli

/**
 * The launchers for the standalone CLI tools, configured from the
 * `ISMA_COMPILER_CLI`/`ISMA_RUNNER_CLI` environment variables or the
 * `isma.compiler.cli`/`isma.runner.cli` system properties.
 */
class CliToolLaunchers {
    val compiler = CliLauncher("lisma-compiler-cli", "ISMA_COMPILER_CLI", "isma.compiler.cli")
    val runner = CliLauncher("simulation-runner-cli", "ISMA_RUNNER_CLI", "isma.runner.cli")
}
