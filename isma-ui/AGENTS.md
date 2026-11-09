# ISMA-UI

User interface module for the ISMA application, built with Kotlin and JavaFX.

## Technology Stack

- **Language:** Kotlin
- **UI Framework:** JavaFX (plain, no TornadoFX)
- **Build System:** Gradle with Kotlin DSL

## Modules

### app/
Main application entry point. Launches the UI and manages the LSP process lifecycle.

- **Main class:** `ru.isma.next.app.launcher.IsmaApplication`
- **Module:** `isma.ui.app.main`
- **Dependencies:** All other isma-ui modules, Koin DI

### domain/
Domain models and business logic for UI layer.

- Pure Kotlin module with no UI dependencies
- Used by other UI modules for shared domain concepts

### external-services/
Direct CLI integration layer for the standalone LISMA compiler and simulation runner tools.

- `cli/` — CLI process layer: `CliProcess` (stdin/stdout/stderr + graceful termination), `CliLauncher` (path resolution + launch), `CliToolLaunchers` (compiler/runner launchers), `LismaCompilerCli` (compile/validate via stdin), `SimulationRunnerCli` (run + `--list-methods`), `ActiveSimulation` (streams the runner's binary stdout into a result file and exposes TIME values for progress)
- `SimulationServerFacade` — high-level facade: compile (model ID = local `.class` path), validate, run/monitor/cancel (local simulation IDs), result download, method listing
- `lsp/` — LSP client layer driving the standalone `isma-lsp` language server

### text-editor/
Text editing component for mathematical expressions.

- Uses `fxmisc.richtext` library for rich text editing

### blueprint-editor/
Blueprint/visual editing component.

- Uses JavaFX (100% Kotlin, no FXML)

### toolkit/
Shared UI utilities and components.

- Common JavaFX utilities used across modules

## Building

```bash
./gradlew :isma-ui:app:build
```

## Running

The app requires the standalone CLI tools to be resolvable:

- `ISMA_COMPILER_CLI` env var or `isma.compiler.cli` system property — path to the LISMA compiler CLI launch script
- `ISMA_RUNNER_CLI` env var or `isma.runner.cli` system property — path to the simulation runner CLI launch script
- `ISMA_GRIN_SCRIPT` env var or `isma.grin.script` system property — path to the grin launch script
- `ISMA_LSP_SCRIPT` env var or `isma.lsp.script` system property — path to the isma-lsp launch script

When running via Gradle, these are auto-configured to point to the bundle directory.
