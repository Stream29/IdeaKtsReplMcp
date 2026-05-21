package io.github.stream29.idea.kts.mcp.script.repl

import kotlin.script.experimental.api.ScriptDiagnostic

sealed interface KotlinReplEvalResult {
    data class Success(
        val state: KotlinReplState,
        val value: Any?,
    ) : KotlinReplEvalResult

    data class CompilationError(
        val diagnostics: List<ScriptDiagnostic>,
    ) : KotlinReplEvalResult

    data class EvaluationError(
        val diagnostics: List<ScriptDiagnostic>,
    ) : KotlinReplEvalResult

    data class RuntimeError(
        val cause: Throwable,
    ) : KotlinReplEvalResult

    data object NotEvaluated : KotlinReplEvalResult
}
