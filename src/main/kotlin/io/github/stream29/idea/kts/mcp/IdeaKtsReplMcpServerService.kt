package io.github.stream29.idea.kts.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.github.stream29.idea.kts.mcp.config.IdeaKtsReplMcpSettings
import io.github.stream29.idea.kts.mcp.script.KotlinScriptEvaluator
import io.github.stream29.idea.kts.mcp.script.PsiScriptContext
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration.Companion.milliseconds

private val TOOL_ARGUMENTS_JSON = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class KotlinEvalToolArguments(
    val script: String,
    val resetState: Boolean = false,
    val projectPath: String? = null,
    val projectName: String? = null,
    val timeoutMs: Long? = null,
)

@Service(Service.Level.APP)
class IdeaKtsReplMcpServerService : Disposable {
    private val log = logger<IdeaKtsReplMcpServerService>()
    private val evaluator = KotlinScriptEvaluator()
    private val knownProjects = CopyOnWriteArraySet<Project>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings: IdeaKtsReplMcpSettings
        get() = ApplicationManager.getApplication().getService(IdeaKtsReplMcpSettings::class.java)
    private val serverJob = serviceScope.launch { manageServer() }

    fun registerProject(project: Project) {
        knownProjects.add(project)
    }

    private suspend fun manageServer() {
        var server: EmbeddedServer<*, *>? = null
        var serverHost: String? = null
        var serverPort: Int? = null

        fun stopCurrentServer() {
            runCatching { server?.stop() }
            server = null
            serverHost = null
            serverPort = null
        }

        try {
            settings.stateFlow.collect { state ->
                if (!state.enabled) {
                    stopCurrentServer()
                    log.info("$PLUGIN_NAME MCP server is disabled.")
                    return@collect
                }

                if (server != null && serverHost == state.host && serverPort == state.port) {
                    return@collect
                }

                stopCurrentServer()
                runCatching {
                    createKtorServer(state.host, state.port).also { newServer ->
                        newServer.start(wait = false)
                    }
                }.onSuccess { newServer ->
                    server = newServer
                    serverHost = state.host
                    serverPort = state.port
                    log.info("$PLUGIN_NAME MCP server started at http://${state.host}:${state.port}$MCP_PATH")
                }.onFailure {
                    log.warn("Could not start $PLUGIN_NAME MCP server on ${state.host}:${state.port}", it)
                }
            }
        } finally {
            stopCurrentServer()
        }
    }

    private fun createKtorServer(host: String, port: Int): EmbeddedServer<*, *> =
        embeddedServer(CIO, host = host, port = port) {
            val mcpServer = createMcpServer()
            mcpStreamableHttp(path = MCP_PATH) {
                mcpServer
            }
        }

    private fun createMcpServer(): Server =
        Server(
            serverInfo = Implementation(
                name = PLUGIN_NAME,
                version = PLUGIN_VERSION,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        ).apply {
            addTool(
                name = KOTLIN_EVAL_TOOL_NAME,
                description = KOTLIN_EVAL_TOOL_DESCRIPTION,
                inputSchema = KOTLIN_EVAL_TOOL_SCHEMA,
            ) { request ->
                val arguments = try {
                    TOOL_ARGUMENTS_JSON.decodeFromJsonElement<KotlinEvalToolArguments>(
                        request.arguments ?: JsonObject(emptyMap()),
                    )
                } catch (exception: SerializationException) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent("Invalid tool arguments: ${exception.message}")),
                        isError = true,
                    )
                }
                val project = selectProject(arguments.projectPath, arguments.projectName)
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent("No open IntelliJ project matched the request.")),
                        isError = true,
                    )
                val timeoutMs = (arguments.timeoutMs ?: settings.currentState.scriptTimeoutMs).takeIf { it > 0 }
                    ?: return@addTool CallToolResult(
                        content = listOf(
                            TextContent("timeoutMs must be greater than 0."),
                        ),
                        isError = true,
                    )

                val result = try {
                    withTimeout(timeoutMs.milliseconds) {
                        runInterruptible {
                            evaluator.evaluate(arguments.script, PsiScriptContext(project), arguments.resetState)
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    return@addTool CallToolResult(
                        content = listOf(TextContent("Script evaluation timed out after ${timeoutMs}ms.")),
                        isError = true,
                    )
                }
                CallToolResult(
                    content = listOf(TextContent(result.text)),
                    isError = !result.ok,
                )
            }
        }

    private fun selectProject(projectPath: String?, projectName: String?): Project? {
        val openProjects = ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .ifEmpty { knownProjects.filter { !it.isDisposed } }

        if (projectPath != null) {
            return openProjects.firstOrNull { it.basePath == projectPath }
        }

        if (projectName != null) {
            return openProjects.firstOrNull { it.name == projectName }
        }

        return openProjects.firstOrNull()
    }

    override fun dispose() {
        runBlocking { serverJob.cancelAndJoin() }
        serviceScope.cancel()
        evaluator.close()
        knownProjects.clear()
    }
}
