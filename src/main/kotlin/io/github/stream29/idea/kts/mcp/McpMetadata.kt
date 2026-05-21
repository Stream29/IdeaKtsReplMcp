package io.github.stream29.idea.kts.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val PLUGIN_NAME = "IdeaKtsReplMcp"
internal const val PLUGIN_VERSION = "0.1.0"
internal const val MCP_PATH = "/mcp"

internal const val KOTLIN_EVAL_TOOL_NAME = "kotlin_eval"

internal val KOTLIN_EVAL_TOOL_DESCRIPTION = """
    Execute unrestricted Kotlin script inside the IntelliJ process.
    The script has a single implicit receiver exposing project: com.intellij.openapi.project.Project.
    Tool output is the script's final expression value; stdout and stderr are not captured or returned.
    For PSI, VFS, document, editor, and UI operations, use IntelliJ Platform APIs directly.
    To keep execution concurrency-safe, explicitly switch to the IDE thread with
    ApplicationManager.getApplication().invokeAndWait { ... } when required, and use the
    platform read/write APIs such as ReadAction and WriteCommandAction around PSI access.
""".trimIndent()

internal val KOTLIN_EVAL_TOOL_SCHEMA = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("script") {
            put("type", "string")
            put(
                "description",
                """
                    Kotlin script. The only provided convenience value is project.
                    Return data as the script's final expression; stdout and stderr are not tool output.
                    Use IntelliJ Platform APIs directly. For concurrency safety, call
                    ApplicationManager.getApplication().invokeAndWait { ... } when an operation must
                    run on the IDE thread, and wrap PSI access/mutation with the platform read/write APIs.
                """.trimIndent(),
            )
        }
        putJsonObject("projectPath") {
            put("type", "string")
            put("description", "Optional IntelliJ project base path.")
        }
        putJsonObject("projectName") {
            put("type", "string")
            put("description", "Optional IntelliJ project name.")
        }
        putJsonObject("resetState") {
            put("type", "boolean")
            put("description", "Reset this project's Kotlin script state before evaluating the script.")
        }
    },
    required = listOf("script"),
)
