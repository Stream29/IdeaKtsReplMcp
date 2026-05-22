package io.github.stream29.idea.kts.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal const val PLUGIN_NAME = "IdeaKtsReplMcp"
internal const val PLUGIN_VERSION = "0.1.2"
internal const val MCP_PATH = "/mcp"

internal const val KOTLIN_EVAL_TOOL_NAME = "kotlin_eval"
internal const val KOTLIN_EVAL_DEFAULT_TIMEOUT_MS = 60_000L

internal val KOTLIN_EVAL_TOOL_DESCRIPTION = """
    Evaluate an unrestricted script inside the running JetBrains IDE process.

    The script language is Kotlin, but the exposed capabilities come from the current IntelliJ Platform IDE
    and the plugins loaded in that IDE, such as WebStorm, PyCharm, IntelliJ IDEA, GoLand, or other JetBrains IDEs.
    Do not assume Java, Kotlin, Gradle, Python, JavaScript, or GitHub APIs are present; probe plugin availability
    or class availability before using product- or language-specific APIs.

    This is a stateful project-scoped REPL. The script has one implicit receiver:
    project: com.intellij.openapi.project.Project. Top-level declarations from previous calls are kept, so
    you can define variables, helper functions, imports, and cached services once, then reuse them in later calls.
    Pass resetState=true when you want to clear that project's REPL history.

    Return contract:
    - The tool result is the script's final expression value.
    - stdout and stderr are not captured or returned. Do not use println as the answer channel.
    - Return concise strings or structured values from the final expression, for example:
      listOf("a", "b").joinToString("\n")

    Timeout behavior:
    - Scripts time out after the configured default unless timeoutMs is provided for a specific call.
    - timeoutMs must be greater than 0.
    - Blocking waits that respond to thread interruption, such as Thread.sleep or CountDownLatch.await,
      are interrupted on timeout. Pure CPU loops may not stop safely; avoid unbounded loops.
    - For long IDE operations, prefer starting asynchronous work and polling later instead of blocking the
      current MCP call.

    Threading and write safety:
    - Prefer IntelliJ coroutine APIs in scripts. Useful imports:
      import com.intellij.openapi.application.readAction
      import com.intellij.openapi.application.smartReadAction
      import com.intellij.openapi.application.writeAction
      import com.intellij.openapi.application.writeIntentReadAction
      import com.intellij.openapi.application.EDT
      import com.intellij.openapi.command.writeCommandAction
    - Signatures and call shapes:
      suspend fun <T> readAction(action: () -> T): T
      suspend fun <T> smartReadAction(project: Project, action: () -> T): T
      suspend fun <T> writeAction(action: () -> T): T
      suspend fun <T> writeIntentReadAction(action: () -> T): T
      suspend fun <T> writeCommandAction(project: Project, commandName: String, action: () -> T): T
      val Dispatchers.EDT: CoroutineContext
    - For PSI/VFS/model reads, use readAction { ... }.
    - For project-wide PSI analysis, index access, resolve, or anything that requires indexes, prefer
      smartReadAction(project) { ... }; it waits for smart mode and runs as a cancellable read action.
    - For PSI/document/project mutations, use writeAction { ... } or writeCommandAction(project, "Name") { ... }.
    - For model reads that must stay on the UI thread, use writeIntentReadAction { ... }.
    - For UI-only work, switch to withContext(Dispatchers.EDT) { ... }; prefer Dispatchers.EDT over Dispatchers.Main
      in IntelliJ Platform code.
    - Commit documents before reading PSI if editor changes matter:
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    - Do not run long blocking waits on the EDT. Start the operation on the proper IDE API and wait from a
      background thread only when necessary.

    Useful IntelliJ Platform API entry points:
    - PSI/project files: PsiManager, PsiDocumentManager, FilenameIndex, GlobalSearchScope, VfsUtilCore.
    - Editors/documents: FileEditorManager, FileDocumentManager, EditorFactory, Document.
    - Inspections/highlighting: DaemonCodeAnalyzerEx, HighlightInfo, IntentionManager.
    - Refactoring: RefactoringFactory, language-specific PSI refactoring APIs, WriteCommandAction.
    - Gradle sync/builds: ExternalSystemUtil with GradleConstants.SYSTEM_ID; prefer this over shelling out to gradlew
      when validating IDE integration, but only if the Gradle plugin is available in the current IDE.
    - Project/module model: ProjectManager, ModuleManager, ProjectRootManager, ProjectJdkTable.
    - Symbol navigation: PsiElement.references, PsiReference.resolve(), PsiElement.navigationElement,
      OpenFileDescriptor, FileEditorManager.
    - Search Everywhere: SearchEverywhereManager, SearchEverywhereManagerImpl, SearchEverywhereContributor.
    - Code assistance: ShowIntentionsPass for context actions/quick fixes; CompletionService,
      CompletionParameters, LookupElement, LookupElementPresentation for completion candidates.

    Common patterns:
    - Start a long task now, poll it later. Because the REPL is stateful, top-level Deferred/Job/Channel
      values survive across calls:
      import kotlinx.coroutines.*
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val task = scope.async {
          // long IDE operation; use readAction/smartReadAction/writeCommandAction as needed
          "done"
      }
      "started"
    - Poll the task in a later call without blocking:
      if (task.isCompleted) runBlocking { task.await() } else "still running"
    - Cancel a stored task/scope:
      task.cancel()
      scope.cancel()
    - Use a Channel for streaming progress across calls:
      import kotlinx.coroutines.channels.Channel
      val progress = Channel<String>(Channel.UNLIMITED)
      val progressTask = scope.launch {
          progress.trySend("started")
          // work...
          progress.trySend("finished")
      }
      "scheduled"
    - Poll Channel progress later:
      buildList {
          while (true) {
              val message = progress.tryReceive().getOrNull() ?: break
              add(message)
          }
      }.joinToString("\n").ifBlank { "no new progress" }
    - Avoid awaiting long tasks in the same call that starts them. Return "started" and poll with a
      subsequent kotlin_eval call.
    - Read PSI safely:
      withContext(Dispatchers.EDT) {
          PsiDocumentManager.getInstance(project).commitAllDocuments()
      }
      readAction {
          project.name
      }
    - Search or resolve with indexes:
      smartReadAction(project) {
          // FilenameIndex, StubIndex, resolve, project-wide PSI analysis
      }
    - Resolve a local symbol to upstream source or the IDE's decompiled PSI, similar to Command+Click:
      val target = element.references.firstNotNullOfOrNull { it.resolve() }
      val navigation = target?.navigationElement
      val file = navigation?.containingFile
      val virtualFile = file?.virtualFile
      listOf(
          navigation?.text?.take(500),
          virtualFile?.url,
          file?.javaClass?.name,
      ).joinToString("\n")
      navigationElement often maps library .class PSI to attached sources, if available. If sources are not
      attached, the IDE may still provide a decompiled PSI file depending on the installed language plugin.
    - Optionally open the resolved source/decompiled file in the editor:
      val descriptor = OpenFileDescriptor(project, virtualFile, navigation.textOffset)
      FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    - Change code safely:
      writeCommandAction(project, "Update code") {
          // edit Document or PSI here
      }
    - Run Gradle through the IDE:
      create ExternalSystemTaskExecutionSettings, set externalProjectPath/project system/taskNames,
      then call ExternalSystemUtil.runTask(..., GradleConstants.SYSTEM_ID, callback, ProgressExecutionMode.IN_BACKGROUND_ASYNC, true).
    - Open Search Everywhere from script:
      import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
      import com.intellij.openapi.actionSystem.*
      import com.intellij.openapi.actionSystem.impl.SimpleDataContext
      withContext(Dispatchers.EDT) {
          val dataContext = SimpleDataContext.builder()
              .add(CommonDataKeys.PROJECT, project)
              .build()
          val event = AnActionEvent.createFromAnAction(
              ActionManager.getInstance().getAction("SearchEverywhere"),
              null,
              ActionPlaces.UNKNOWN,
              dataContext,
          )
          SearchEverywhereManager.getInstance(project).show(
              "ActionSearchEverywhereContributor",
              "Github.Share",
              event,
          )
      }
      Useful tab/provider ids include ActionSearchEverywhereContributor, FileSearchEverywhereContributor,
      ClassSearchEverywhereContributor, SymbolSearchEverywhereContributor, TextSearchContributor, and Vcs.Git.
    - Query Search Everywhere contributors without relying on UI selection:
      import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl
      import com.intellij.openapi.progress.EmptyProgressIndicator
      val contributors = withContext(Dispatchers.EDT) {
          SearchEverywhereManagerImpl.createContributors(event, project, true)
      }
      val actionContributor = contributors.first {
          it.searchProviderId == "ActionSearchEverywhereContributor"
      }
      val results = readAction {
          actionContributor.search("Share Project on GitHub", EmptyProgressIndicator())
      }
      Do not block the EDT waiting for Search Everywhere futures; start async work and poll later if needed.
    - Read current context actions/quick fixes, like the editor light bulb:
      import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
      val editorAndFile = withContext(Dispatchers.EDT) {
          val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: error("no editor")
          val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: error("no PSI file")
          editor to file
      }
      val intentionsInfo = readAction {
          ShowIntentionsPass.getActionsToShow(editorAndFile.first, editorAndFile.second)
      }
      ShowIntentionsPass.getActionsToShow must run on a background thread under readAction, not on the EDT.
      Inspect its intentionsToShow, errorFixesToShow, inspectionFixesToShow, guttersToShow, and
      notificationActionsToShow fields if the public API is not enough.
    - Trigger completion and read the active lookup, when the editor context can show a completion popup:
      import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
      import com.intellij.codeInsight.completion.CompletionType
      import com.intellij.codeInsight.lookup.LookupManager
      import com.intellij.codeInsight.lookup.LookupElementPresentation
      withContext(Dispatchers.EDT) {
          val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: error("no editor")
          CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor, 1, false)
          val lookup = LookupManager.getActiveLookup(editor)
          lookup?.items.orEmpty().map { item ->
              val presentation = LookupElementPresentation()
              item.renderElement(presentation)
              "${'$'}{item.lookupString} ${'$'}{presentation.tailText ?: ""} ${'$'}{presentation.typeText ?: ""}"
          }.joinToString("\n").also {
              LookupManager.getInstance(project).hideActiveLookup()
          }
      }
    - Collect completion candidates without depending on the popup:
      import com.intellij.codeInsight.completion.*
      import com.intellij.codeInsight.lookup.LookupElementPresentation
      import com.intellij.util.Consumer
      val results = mutableListOf<CompletionResult>()
      readAction {
          val position = psiFile.findElementAt(offset - 1) ?: error("no completion position")
          val params = CompletionParameters(position, psiFile, CompletionType.BASIC, offset, 1, editor, process)
          params.setTestingMode(true)
          CompletionService.getCompletionService().performCompletion(params, Consumer { result ->
              results += result
          })
      }
      CompletionParameters needs a real CompletionProcess/CompletionProcessEx in some contributors. If you do not
      have one, the active lookup route is often simpler; otherwise provide a small CompletionProcessEx proxy with
      editor, project, caret, offset map, host offsets, and user data.

    Project selection:
    - If projectPath is provided, it selects an open project by base path.
    - If projectName is provided, it selects an open project by name.
    - If neither is provided, the first open project is used.
""".trimIndent()

internal val KOTLIN_EVAL_TOOL_SCHEMA = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("script") {
            put("type", "string")
            put(
                "description",
                """
                    Script executed inside the current JetBrains IDE as a stateful project-scoped REPL.
                    The script language is Kotlin, but available IDE APIs depend on the current product
                    and installed plugins.
                    The implicit receiver exposes project: com.intellij.openapi.project.Project.
                    Return data as the final expression; stdout and stderr are not tool output.
                    Prefer coroutine APIs: readAction/smartReadAction for PSI/model reads,
                    writeAction/writeCommandAction for mutations, and withContext(Dispatchers.EDT)
                    for short UI-only work.
                """.trimIndent(),
            )
        }
        putJsonObject("projectPath") {
            put("type", "string")
            put("description", "Optional IDE project base path.")
        }
        putJsonObject("projectName") {
            put("type", "string")
            put("description", "Optional IDE project name.")
        }
        putJsonObject("resetState") {
            put("type", "boolean")
            put("description", "Reset this project's REPL state before evaluating the script.")
        }
        putJsonObject("timeoutMs") {
            put("type", "integer")
            put(
                "description",
                "Optional evaluation timeout in milliseconds for this call. Must be greater than 0.",
            )
        }
    },
    required = listOf("script"),
)
