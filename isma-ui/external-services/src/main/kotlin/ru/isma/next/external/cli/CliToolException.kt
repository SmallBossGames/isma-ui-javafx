package ru.isma.next.external.cli

/**
 * A CLI tool failed outside its documented contract (an unexpected exit code,
 * a launch failure). Carries the tool's stderr for diagnostics.
 */
class CliToolException(toolName: String, exitCode: Int, stderr: String) :
    IllegalStateException("$toolName exited with code $exitCode: $stderr")
