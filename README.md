# IdeaKtsReplMcp

[简体中文](README.zh-CN.md)

Give your coding agent a live connection to IntelliJ IDEA.

## Build And Install Locally

For plugin development, launch a sandbox IDE:

```bash
./gradlew runIde
```

For local installation into your regular IntelliJ IDEA, build the plugin zip:

```bash
./gradlew buildPlugin
```

The installable plugin is created under:

```text
build/distributions/IdeaKtsReplMcp-<version>.zip
```

Install it from IntelliJ IDEA:

- Open `Settings | Plugins`.
- Click the gear icon.
- Choose `Install Plugin from Disk...`.
- Select the zip from `build/distributions`.
- Restart the IDE when prompted.

Open any project after the plugin is installed. The MCP endpoint is local by default:

```text
http://127.0.0.1:39393/mcp
```

IdeaKtsReplMcp is an IntelliJ IDEA plugin that exposes a local MCP tool backed by a stateful Kotlin REPL inside the IDE process. The agent is no longer limited to reading files from disk. It can ask IDEA what it knows: the project model, PSI trees, indexes, inspections, refactorings, Gradle integration, code completion, context actions, navigation targets, and authenticated IDE services.

The interesting part is not "run Kotlin in IDEA". The interesting part is that an agent can finally see code as a syntax tree, gradually unfold the semantic information behind each node, and use the IDE's own machinery to make precise local edits and refactorings.

Instead of stuffing whole files into a prompt and hoping the model keeps the structure straight, the agent can inspect the shape of the program first: declarations, expressions, references, scopes, diagnostics, navigation targets. It can load only the next layer of information when it needs it. That makes code understanding feel less like text prediction and more like working with a living development environment.

## Use Cases

### See The Shape Before Reading The Details

Large files are easier to understand when the agent can first see their structure.

The agent can begin with a PSI parent chain, not a full file dump. It sees whether the caret is inside a call argument, a lambda body, a property initializer, an import, or a class declaration. Then it can ask for only the next useful layer: siblings in the same block, the containing function signature, the surrounding class, or references to the same symbol.

For example, it can ask IDEA for the PSI path around the caret:

```kotlin
val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: error("no editor")
val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: error("no PSI")
val element = file.findElementAt(editor.caretModel.offset) ?: error("no element")
generateSequence(element) { it.parent }
    .take(8)
    .joinToString("\n") { "${it.javaClass.simpleName}: ${it.text.take(80)}" }
```

Returns:

```text
KtNameReferenceExpression: createMcpServer
KtCallExpression: createMcpServer()
KtProperty: val mcpServer = createMcpServer()
KtBlockExpression: val mcpServer = createMcpServer() mcpStreamableHttp(path = MCP_PATH) { mcpServer }
KtFunctionLiteral: { val mcpServer = createMcpServer() mcpStreamableHttp(path = MCP_PATH) { mcpServer } }
KtLambdaExpression: { val mcpServer = createMcpServer() mcpStreamableHttp(path = MCP_PATH) { mcpServer } }
KtLambdaArgument: { val mcpServer = createMcpServer() mcpStreamableHttp(path = MCP_PATH) { mcpServer } }
KtCallExpression: embeddedServer(CIO, host = host, port = port) { val mcpServer = createMcpServer() mcpStreamableH
```

The return value is the exciting part: the agent gets a compact route through the program shape before it reads the surrounding text.

That changes editing behavior. A local edit can stay local because the agent knows the boundary of the expression it is touching. A refactor can move up to IDE machinery because the agent knows when the node is a declaration, a reference, or a package boundary.

### Follow The Code, Not The Text

Ask the agent to explain where a call really goes.

Instead of grepping through source files, it can start from the PSI element under the caret, resolve references, and ask IDEA for the navigation element. If the target lives in a dependency, IDEA can still lead it to attached sources or a decompiled view. If the target is generated or indexed, IDEA already knows where the meaningful representation is.

The script looks like a programmable Command-click:

```kotlin
val serverCall = file.text.indexOf("Server(\n")
val element = file.findElementAt(serverCall) ?: error("no element")
val target = generateSequence(element) { it.parent }
    .flatMap { it.references.asSequence() }
    .firstNotNullOfOrNull { it.resolve() }
    ?.navigationElement
listOf(
    target?.containingFile?.virtualFile?.url,
    target?.text?.replace(Regex("\\s+"), " ")?.take(400),
).joinToString("\n")
```

Returns:

```text
jar:///Users/stream/.gradle/caches/modules-2/files-2.1/io.modelcontextprotocol/kotlin-sdk-server-jvm/0.12.0/.../kotlin-sdk-server-jvm-0.12.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/Server.kt
( protected val serverInfo: Implementation, protected val options: ServerOptions, protected val instructionsProvider: (() -> String)? = null, block: Server.() -> Unit = {}, )
```

The result is not a list of matching strings. It is a path through the same semantic links you use when you Command-click. The agent can quote the declaration it reached, inspect its parameters, then decide whether to keep following the chain.

### Search The IDE, Not Just The Repository

Search Everywhere is useful because it is not one search box. It is many IDE search surfaces behind one gesture.

The agent can ask IDEA which contributors are available, then search in the right place: files when it needs a path, symbols when it needs an API, actions when it needs a command, Git when it needs a branch or commit, endpoints when it needs a route, run configurations when it needs to execute something.

It can first ask what Search Everywhere can see in this IDE:

```kotlin
val contributors = withContext(Dispatchers.EDT) {
    SearchEverywhereManagerImpl.createContributors(event, project, true)
}
contributors.joinToString("\n") {
    "${it.searchProviderId}: ${it.groupName}"
}
```

Returns:

```text
FileSearchEverywhereContributor: Files
NonIndexableFilesSEContributor: Non-indexable files
ActionSearchEverywhereContributor: Actions
CalculatorSEContributor: Calculator
RecentFilesSEContributor: Recent Files
TopHitSEContributor: Top Hit
TextSearchContributor: Text
ClassSearchEverywhereContributor: Classes
SymbolSearchEverywhereContributor: Symbols
RunConfigurationsSEContributor: Run Configurations
YAMLKeysSearchEverywhereContributor: Config keys
UrlSearchEverywhereContributor: Endpoints
DbObjectsSEContributor: Database
Vcs.Git: Git
```

This is a very different starting point from `rg`. The agent can discover "Share Project on GitHub", "Gradle sync", "Run Configurations", or a symbol name through the IDE's own navigation model, then act on the result with the corresponding IntelliJ API.

### Let IDEA Tell The Agent What To Do

When code has a warning, the agent can ask IDEA for the same context actions you see in the editor.

The script can read the light-bulb menu directly:

```kotlin
val actions = readAction {
    ShowIntentionsPass.getActionsToShow(editor, file)
}
actions.intentionsToShow.joinToString("\n") { it.action.text }
```

Returns:

```text
intentionsToShow:
- Remove explicit type specification
- Split property declaration
errorFixesToShow:
inspectionFixesToShow:
```

That means the agent can read the light-bulb menu before editing. It can separate intentions from error fixes and inspection fixes, show the user the available moves, and apply the one that matches the goal. For simple cleanup, IDEA may already know the safest transformation. For ambiguous repairs, the action list gives the agent a grounded menu instead of a blank page.

This also makes review work sharper. The agent can intentionally create a small unresolved or suspicious construct, ask IDEA what fixes appear, and learn which platform quick fix API should be used for the real change.

### Complete With Local Knowledge

Code completion is a surprisingly powerful oracle.

When the agent is unsure which API exists in this exact IDE, plugin version, dependency graph, or source set, it can ask IDEA for completion candidates at the current location. The answer is scoped to this project, this language, this classpath, and this partially typed code.

That is just another scriptable IDE query:

```kotlin
val results = mutableListOf<CompletionResult>()
CompletionService.getCompletionService().performCompletion(params, Consumer { result ->
    results += result
})
results.take(6).joinToString("\n") { result ->
    val item = result.lookupElement
    val presentation = LookupElementPresentation()
    item.renderElement(presentation)
    "${item.lookupString} ${presentation.typeText ?: ""}"
}
```

Returns:

```text
Rename (⇧F6)
Rename (⇧F6)
Surround with 'try / finally' (⌥⌘T)
Surround with 'try / finally' (⌥⌘T)
Surround with 'try / catch / finally' (⌥⌘T)
Surround with 'try / catch / finally' (⌥⌘T)
```

Sometimes the result is a member or extension function. Sometimes it is a keyword, live template, or command-style completion such as Rename or Surround With. Either way, the agent learns what IDEA thinks is a valid next move at that exact caret position.

### Refactor With The IDE Watching

Renaming a package by text replacement is fragile.

Through IDEA, the agent can first inspect the PSI node and confirm that it is touching a declaration, package directive, class, function, or reference. Then it can invoke refactoring-aware APIs that understand imports, Kotlin/Java PSI, references, and project structure.

Before a rename, it can ask IDEA what would be touched:

```kotlin
val declaration = generateSequence(element) { it.parent }
    .first { it.javaClass.simpleName == "KtProperty" }
val references = ReferencesSearch.search(declaration).findAll()
buildString {
    appendLine("declaration=${declaration.text}")
    appendLine("references=${references.size}")
    references.forEach { appendLine(it.element.containingFile.virtualFile.path) }
}
```

Returns:

```text
declaration=internal const val KOTLIN_EVAL_TOOL_NAME = "kotlin_eval"
references=1
- src/main/kotlin/io/github/stream29/idea/kts/mcp/IdeaKtsReplMcpServerService.kt:127 KOTLIN_EVAL_TOOL_NAME
```

The important shift is that the agent does not need to simulate a refactor by editing many files. It can ask IDEA to perform the semantic operation, then inspect the resulting diff. The IDE does the reference chasing; the agent keeps the intent and verification loop.

### Build, Sync, And Diagnose In The Same Place

Without Gradle sync, a lot of PSI becomes half-blind.

The agent can ask IDEA to run Gradle sync or Gradle tasks through the IDE's External System integration, then observe the result from inside the same project model it is about to inspect. After sync, indexes, dependencies, source sets, generated sources, and diagnostics all line up with what the developer sees.

It can also ask IDEA what Gradle has already imported:

```kotlin
val info = ExternalSystemUtil.getExternalProjectInfo(
    project,
    GradleConstants.SYSTEM_ID,
    project.basePath!!,
)
val children = info?.externalProjectStructure?.children.orEmpty()
buildString {
    appendLine("externalSystem=${GradleConstants.SYSTEM_ID.id}")
    appendLine("linkedProjectPath=${info?.externalProjectPath}")
    appendLine("projectName=${info?.externalProjectStructure?.data?.externalName}")
    appendLine("rootChildren=${children.size}")
    children.groupBy { it.key.dataType.toString() }
        .entries
        .sortedBy { it.key }
        .forEach { (type, nodes) -> appendLine("- $type: ${nodes.size}") }
}
```

Returns:

```text
externalSystem=GRADLE
linkedProjectPath=/Users/stream/ACodeSpace/demo/PsiAgent
projectName=IdeaKtsReplMcp
rootChildren=95
- com.intellij.openapi.externalSystem.model.project.LibraryData: 71
- com.intellij.openapi.externalSystem.model.project.ModuleData: 1
- org.jetbrains.plugins.gradle.model.ExternalProject: 1
- org.jetbrains.plugins.gradle.model.VersionCatalogsModel: 1
```

That means build feedback and semantic analysis stay connected. The agent can sync, wait, inspect PSI again, read highlighting or problems, and only then decide whether a code edit is needed.

### Use The IDE As The Trusted Desktop

If IDEA already has your GitHub account, the agent can use that authenticated IDE integration.

For example, it can create a public GitHub repository, configure the project remote, and push the current branch through IDEA's Git and GitHub plugins, without asking for a separate token.

It can verify the trusted IDE context before acting:

```kotlin
val githubLoader = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.github"))!!.pluginClassLoader!!
fun gh(name: String) = githubLoader.loadClass(name)
val account = gh("org.jetbrains.plugins.github.authentication.GHAccountsUtil")
    .getMethod("getSingleOrDefaultAccount", Project::class.java)
    .invoke(null, project)
val token = gh("org.jetbrains.plugins.github.util.GHCompatibilityUtil")
    .getMethod("getOrRequestToken", account.javaClass, Project::class.java)
    .invoke(null, account, project) as String
val gitLoader = PluginManagerCore.getPlugin(PluginId.getId("Git4Idea"))!!.pluginClassLoader!!
val manager = gitLoader.loadClass("git4idea.repo.GitRepositoryManager")
    .getMethod("getInstance", Project::class.java)
    .invoke(null, project)
val repositories = manager.javaClass.getMethod("getRepositories").invoke(manager) as List<*>
val accountName = account.javaClass.getMethod("getName").invoke(account)
val accountServer = account.javaClass.getMethod("getServer").invoke(account)
buildString {
    appendLine("githubAccount=$accountName@$accountServer")
    appendLine("tokenAvailable=${token.isNotBlank()}")
    appendLine("gitRepositories=${repositories.size}")
    val remotes = repositories.first()!!.javaClass.getMethod("getRemotes").invoke(repositories.first()) as Collection<*>
    remotes.forEach { remote ->
        val name = remote!!::class.java.getMethod("getName").invoke(remote)
        val urls = remote::class.java.getMethod("getUrls").invoke(remote)
        appendLine("remote.$name=$urls")
    }
}
```

Returns:

```text
githubAccount=Stream29@github.com
tokenAvailable=true
gitRepositories=1
remote.origin=[https://github.com/Stream29/IdeaKtsReplMcp.git]
```

This is not limited to GitHub. The broader pattern is that the IDE is already the trusted desktop context: accounts, VCS roots, run configurations, Gradle projects, and open editors are all there. The agent can use that context instead of rebuilding it from scratch.

## Tool

The plugin exposes one MCP tool:

- `kotlin_eval`
  - Runs Kotlin script inside IntelliJ IDEA.
  - Keeps state per open project.
  - Provides `project` as the main entry point.
  - Returns the script's final expression value.
  - Does not use stdout as the answer channel.

The tool description itself contains the practical script patterns for PSI reads, smart read actions, write commands, Search Everywhere, context actions, completion, Gradle, symbol navigation, and long-running async work.

## Settings

Open `Settings | Tools | IdeaKtsReplMcp`.

From there you can:

- Enable or disable the MCP server.
- Configure the bind host.
- Configure the starting port.

## Safety

This is unrestricted code execution inside the IDE process.

Keep it bound to localhost unless you intentionally want to expose your IDE. Scripts can access project files, local files, IDE services, credentials available to IDEA integrations, and anything reachable through IntelliJ Platform APIs.
