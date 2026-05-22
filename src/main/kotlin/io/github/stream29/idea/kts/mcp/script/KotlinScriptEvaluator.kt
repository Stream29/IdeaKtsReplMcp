package io.github.stream29.idea.kts.mcp.script

import com.intellij.openapi.project.Project
import io.github.stream29.idea.kts.mcp.script.repl.KotlinReplEngine
import io.github.stream29.idea.kts.mcp.script.repl.KotlinReplEvalResult
import io.github.stream29.idea.kts.mcp.script.repl.KotlinReplState
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource

class KotlinScriptEvaluator : AutoCloseable {
    private val scriptClasspath = collectScriptClasspath()
    private val engine = KotlinReplEngine(scriptClasspath = scriptClasspath)
    private val states = ConcurrentHashMap<String, KotlinReplState>()
    private val scriptIds = AtomicLong()

    init {
        System.setProperty(
            "kotlin.script.classpath",
            scriptClasspath.joinToString(File.pathSeparator) { it.absolutePath },
        )
    }

    @Synchronized
    fun evaluate(script: String, context: PsiScriptContext, resetState: Boolean = false): EvaluationResult {
        val stateKey = context.project.locationKey()
        if (resetState) {
            states.remove(stateKey)
        }

        val evalResult = try {
            val result = engine.eval(
                state = states[stateKey] ?: initialState(),
                source = script.toScriptSource("IdeaKtsReplMcp_${scriptIds.incrementAndGet()}.main.kts"),
                additionalImplicitReceivers = listOf(context),
            )
            if (result is KotlinReplEvalResult.Success) {
                states[stateKey] = result.state
            }
            result
        } catch (throwable: Throwable) {
            KotlinReplEvalResult.RuntimeError(throwable)
        }

        return evalResult.toEvaluationResult()
    }

    override fun close() {
        states.clear()
        engine.close()
    }

    private fun initialState(): KotlinReplState =
        KotlinReplState.Empty.copy(
            lastSnippetClassLoader = PsiScriptContext::class.java.classLoader,
        )

    private fun Project.locationKey(): String =
        basePath ?: locationHash
}

private fun KotlinReplEvalResult.toEvaluationResult(): EvaluationResult =
    when (this) {
        is KotlinReplEvalResult.Success -> EvaluationResult(
            ok = true,
            text = value?.toString() ?: "null",
        )

        is KotlinReplEvalResult.CompilationError -> EvaluationResult(
            ok = false,
            text = diagnostics.renderReports(),
        )

        is KotlinReplEvalResult.EvaluationError -> EvaluationResult(
            ok = false,
            text = diagnostics.renderReports(),
        )

        is KotlinReplEvalResult.RuntimeError -> EvaluationResult(
            ok = false,
            text = cause.stackTraceToString(),
        )

        KotlinReplEvalResult.NotEvaluated -> EvaluationResult(
            ok = false,
            text = "Script was not evaluated.",
        )
    }

private fun List<ScriptDiagnostic>.renderReports(): String =
    joinToString(separator = "\n") { it.render() }

data class EvaluationResult(
    val ok: Boolean,
    val text: String,
)

private fun collectScriptClasspath(): List<File> {
    val files = linkedSetOf<File>()
    files.addCodeSourceOf(PsiScriptContext::class.java)
    files.addCodeSourceOf(KotlinScriptEvaluator::class.java)

    System.getProperty("java.class.path")
        ?.split(File.pathSeparator)
        ?.map(::File)
        ?.filter { it.exists() }
        ?.onEach(files::add)
        ?.forEach { classpathEntry ->
            val productRoot = classpathEntry.parentFile
                ?.takeIf { it.name == "lib" }
                ?.parentFile
                ?.takeIf { it.resolve("plugins").isDirectory }
            productRoot?.resolve("plugins")?.addProductPluginJarsTo(files)
        }

    System.getProperty("plugin.path")
        ?.let(::File)
        ?.takeIf { it.exists() }
        ?.let { pluginPath ->
            files.add(pluginPath)
            pluginPath.resolve("lib").addJarFilesTo(files)
        }

    collectClasspathFiles(Thread.currentThread().contextClassLoader, files, linkedSetOf())
    collectClasspathFiles(PsiScriptContext::class.java.classLoader, files, linkedSetOf())

    return files.toList()
}

private fun collectClasspathFiles(
    classLoader: ClassLoader?,
    files: MutableSet<File>,
    visited: MutableSet<ClassLoader>,
) {
    if (classLoader == null || !visited.add(classLoader)) {
        return
    }

    if (classLoader is URLClassLoader) {
        classLoader.urLs.mapNotNullTo(files) { it.toClasspathFile() }
    }

    for (methodName in listOf("getURLs", "getUrls")) {
        val method = classLoader.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: continue
        val value = runCatching { method.invoke(classLoader) }.getOrNull()
        when (value) {
            is Array<*> -> value.filterIsInstance<URL>().mapNotNullTo(files) { it.toClasspathFile() }
            is Iterable<*> -> value.filterIsInstance<URL>().mapNotNullTo(files) { it.toClasspathFile() }
        }
    }

    for (methodName in listOf("getFiles", "getLibDirectories")) {
        val method = classLoader.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: continue
        val value = runCatching { method.invoke(classLoader) }.getOrNull()
        when (value) {
            is Array<*> -> value.mapNotNullTo(files) { it.toClasspathFile() }
            is Iterable<*> -> value.mapNotNullTo(files) { it.toClasspathFile() }
        }
    }

    collectClasspathFiles(classLoader.parent, files, visited)
}

private fun MutableSet<File>.addCodeSourceOf(type: Class<*>) {
    type.protectionDomain
        ?.codeSource
        ?.location
        ?.toClasspathFile()
        ?.let(::add)
}

private fun Any?.toClasspathFile(): File? =
    when (this) {
        is File -> takeIf { it.exists() }
        is URL -> toClasspathFile()
        is java.nio.file.Path -> toFile().takeIf { it.exists() }
        is String -> File(this).takeIf { it.exists() }
        else -> null
    }

private fun URL.toClasspathFile(): File? =
    if (protocol == "file") runCatching { File(toURI()).takeIf { it.exists() } }.getOrNull() else null

private fun File.addJarFilesTo(files: MutableSet<File>) {
    takeIf { it.isDirectory }
        ?.listFiles { file -> file.isFile && file.extension == "jar" }
        ?.forEach(files::add)
}

private fun File.addProductPluginJarsTo(files: MutableSet<File>) {
    listFiles { file -> file.isDirectory }?.forEach { pluginDir ->
        pluginDir.resolve("lib").addJarFilesTo(files)
    }
}
