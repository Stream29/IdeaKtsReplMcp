package io.github.stream29.idea.kts.mcp.script.repl

import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.mainKts.MainKtsScript
import org.jetbrains.kotlin.mainKts.configureScriptFileLocationPathVariablesForEvaluation
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.compilationConfiguration
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.api.fileExtension
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.importScripts
import kotlin.script.experimental.api.refineConfigurationBeforeEvaluate
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.CompiledJvmScriptsCache
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.lastSnippetClassLoader
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.BasicJvmScriptClassFilesGenerator
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.util.PropertiesCollection

class KotlinReplEngine(
    private val scriptClasspath: List<File> = emptyList(),
    private val compiledScriptsCacheDir: File = createTempDirectory("kotlin-repl-compiled-scripts-").toFile(),
) : AutoCloseable {
    private val engineHostConfiguration = ScriptingHostConfiguration {
        jvm.compilationCache(CompiledJvmScriptsCache.NoCache)
    }
    private val host = BasicJvmScriptingHost(engineHostConfiguration)
    private val evaluator = BasicJvmScriptEvaluator()
    private val classFilesGenerator = BasicJvmScriptClassFilesGenerator(compiledScriptsCacheDir)
    private val importedScriptInstances = mutableMapOf<SourceCode, Any>()

    fun eval(
        state: KotlinReplState,
        source: SourceCode,
        additionalImplicitReceivers: List<Any> = emptyList(),
    ): KotlinReplEvalResult {
        val compiledScript = host.runInCoroutineContext {
            host.compiler(
                source,
                compilationConfiguration(
                    state = state,
                    additionalReceivers = additionalImplicitReceivers,
                ),
            )
        }

        return when (compiledScript) {
            is ResultWithDiagnostics.Success -> evaluateCompiled(
                state = state,
                compiledScript = compiledScript.value,
                additionalReceivers = additionalImplicitReceivers,
            )

            is ResultWithDiagnostics.Failure -> KotlinReplEvalResult.CompilationError(compiledScript.reports)
        }
    }

    override fun close() = Unit

    private fun compilationConfiguration(
        state: KotlinReplState,
        additionalReceivers: List<Any>,
    ): ScriptCompilationConfiguration =
        createJvmCompilationConfigurationFromTemplate<MainKtsScript>(engineHostConfiguration) {
            fileExtension("kts")
            hostConfiguration.put(engineHostConfiguration)
            jvm {
                dependenciesFromCurrentContext(wholeClasspath = true)
                updateClasspath(scriptClasspath + compiledScriptsCacheDir + state.dependsOnClasspath)
            }
            implicitReceivers.append(
                state.receiverChain(additionalReceivers).map { KotlinType(it::class) },
            )
        }

    private fun evaluateCompiled(
        state: KotlinReplState,
        compiledScript: CompiledScript,
        additionalReceivers: List<Any>,
    ): KotlinReplEvalResult {
        val dependsOnClasspath =
            (state.dependsOnClasspath.asSequence() + compiledScript.dependsOnClasspath().asSequence())
                .map { it.canonicalFile }
                .filterNot { FileUtil.filesEqual(it, compiledScriptsCacheDir.canonicalFile) }
                .toSet()
        val sharedScriptEvaluations = importedScriptInstances.values.associateTo(mutableMapOf()) { scriptInstance ->
            scriptInstance::class to EvaluationResult(
                ResultValue.Unit(scriptInstance::class, scriptInstance),
                null,
            )
        }
        val evaluationConfiguration = evaluationConfiguration(
            state = state,
            compiledScript = compiledScript,
            additionalReceivers = additionalReceivers,
            sharedScriptEvaluations = sharedScriptEvaluations,
        )
        val result = host.runInCoroutineContext {
            evaluator(compiledScript, evaluationConfiguration)
        }
        persistClassFiles(compiledScript, evaluationConfiguration)

        return when (result) {
            is ResultWithDiagnostics.Success -> {
                rememberImportedScriptInstances(
                    compiledScript = compiledScript,
                    evaluationConfiguration = result.value.configuration ?: evaluationConfiguration,
                    sharedScriptEvaluations = sharedScriptEvaluations,
                )
                result.value.returnValue.toEvalResult(
                    state = state,
                    dependsOnClasspath = dependsOnClasspath,
                )
            }

            is ResultWithDiagnostics.Failure -> KotlinReplEvalResult.EvaluationError(result.reports)
        }
    }

    private fun evaluationConfiguration(
        state: KotlinReplState,
        compiledScript: CompiledScript,
        additionalReceivers: List<Any>,
        sharedScriptEvaluations: MutableMap<KClass<*>, EvaluationResult>,
    ): ScriptEvaluationConfiguration =
        ScriptEvaluationConfiguration {
            compilationConfiguration.put(compiledScript.compilationConfiguration)
            constructorArgs.put(listOf(emptyArray<String>()))
            jvm.baseClassLoader(Thread.currentThread().contextClassLoader)
            jvm.lastSnippetClassLoader(
                ImportedScriptsClassLoader(
                    importedClassLoaders = sharedScriptEvaluations.keys.map { it.java.classLoader },
                    parent = state.lastSnippetClassLoader,
                ),
            )
            this[JvmScriptsInstancesSharingMapKey] = sharedScriptEvaluations
            implicitReceivers.append(*state.receiverChain(additionalReceivers).toTypedArray())
            refineConfigurationBeforeEvaluate(::configureScriptFileLocationPathVariablesForEvaluation)
        }

    private fun persistClassFiles(
        compiledScript: CompiledScript,
        evaluationConfiguration: ScriptEvaluationConfiguration,
    ) {
        val result = host.runInCoroutineContext {
            classFilesGenerator(compiledScript, evaluationConfiguration)
        }
        when (result) {
            is ResultWithDiagnostics.Success -> Unit
            is ResultWithDiagnostics.Failure -> {
                if (!result.reports.any { it.message.startsWith("Cannot generate classes: unsupported module type") }) {
                    throw IllegalStateException(
                        result.reports.joinToString(separator = "\n") { it.render() },
                    )
                }
            }
        }
    }

    private fun rememberImportedScriptInstances(
        compiledScript: CompiledScript,
        evaluationConfiguration: ScriptEvaluationConfiguration,
        sharedScriptEvaluations: Map<KClass<*>, EvaluationResult>,
    ) {
        val importedSources =
            compiledScript.compilationConfiguration[ScriptCompilationConfiguration.importScripts].orEmpty()
        for ((index, importedScript) in compiledScript.otherScripts.withIndex()) {
            val importedScriptClass = host.runInCoroutineContext {
                importedScript.getClass(evaluationConfiguration)
            }.let { result ->
                when (result) {
                    is ResultWithDiagnostics.Success -> result.value
                    is ResultWithDiagnostics.Failure -> null
                }
            }
            val scriptInstance = importedScriptClass
                ?.let(sharedScriptEvaluations::get)
                ?.returnValue
                ?.scriptInstance
            val importedSource = importedSources.getOrNull(index)
            if (importedSource != null && scriptInstance != null) {
                importedScriptInstances[importedSource] = scriptInstance
            }
            rememberImportedScriptInstances(
                compiledScript = importedScript,
                evaluationConfiguration = evaluationConfiguration,
                sharedScriptEvaluations = sharedScriptEvaluations,
            )
        }
    }
}

private val JvmScriptsInstancesSharingMapKey =
    PropertiesCollection.Key<MutableMap<KClass<*>, EvaluationResult>>("scriptsInstancesSharingMap")

private class ImportedScriptsClassLoader(
    private val importedClassLoaders: List<ClassLoader>,
    parent: ClassLoader,
) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)
                ?: importedClassLoaders.firstNotNullOfOrNull { classLoader ->
                    try {
                        classLoader.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                        null
                    }
                }
                ?: super.loadClass(name, resolve)
        }
}

private fun KotlinReplState.receiverChain(additionalReceivers: List<Any>): List<Any> =
    additionalReceivers + historyCellReceivers.asReversed()

private fun CompiledScript.dependsOnClasspath(): Set<File> {
    val scriptsToVisit = ArrayDeque(listOf(this))

    return sequence {
        while (scriptsToVisit.isNotEmpty()) {
            val script = scriptsToVisit.removeFirst()
            yield(script)
            scriptsToVisit.addAll(script.otherScripts)
        }
    }
        .flatMap { script ->
            script.compilationConfiguration[ScriptCompilationConfiguration.dependencies]
                .orEmpty()
                .asSequence()
        }
        .filterIsInstance<JvmDependency>()
        .flatMap { it.classpath.asSequence() }
        .toSet()
}

private fun ResultValue.toEvalResult(
    state: KotlinReplState,
    dependsOnClasspath: Set<File>,
): KotlinReplEvalResult =
    when (this) {
        is ResultValue.Value ->
            KotlinReplEvalResult.Success(
                state.copy(
                    historyCellReceivers = state.historyCellReceivers + scriptInstance!!,
                    dependsOnClasspath = dependsOnClasspath,
                    lastSnippetClassLoader = scriptClass!!.java.classLoader,
                ),
                value,
            )

        is ResultValue.Unit ->
            KotlinReplEvalResult.Success(
                state.copy(
                    historyCellReceivers = state.historyCellReceivers + scriptInstance!!,
                    dependsOnClasspath = dependsOnClasspath,
                    lastSnippetClassLoader = scriptClass!!.java.classLoader,
                ),
                Unit,
            )

        is ResultValue.Error -> KotlinReplEvalResult.RuntimeError(error)
        ResultValue.NotEvaluated -> KotlinReplEvalResult.NotEvaluated
    }
