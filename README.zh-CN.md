# IdeaKtsReplMcp

[English](README.md)

让你的编码 Agent 真正接入 IntelliJ IDEA。

## 本地构建与安装

开发插件时，可以启动一个 sandbox IDE：

```bash
./gradlew runIde
```

如果要安装到你日常使用的 IntelliJ IDEA，先构建插件 zip：

```bash
./gradlew buildPlugin
```

可安装的插件包会生成在：

```text
build/distributions/IdeaKtsReplMcp-<version>.zip
```

然后在 IntelliJ IDEA 里安装：

- 打开 `Settings | Plugins`。
- 点击齿轮图标。
- 选择 `Install Plugin from Disk...`。
- 选择 `build/distributions` 里的 zip。
- 按提示重启 IDE。

插件安装后，打开任意项目。MCP endpoint 默认只监听本地：

```text
http://127.0.0.1:39393/mcp
```

IdeaKtsReplMcp 是一个 IntelliJ IDEA 插件。它在 IDE 进程内部提供一个带状态的 Kotlin REPL，并通过本地 MCP 工具暴露出来。Agent 不再只能读取磁盘上的文件。它可以询问 IDEA 已经知道的一切：项目模型、PSI 树、索引、检查结果、重构能力、Gradle 集成、代码补全、上下文动作、符号跳转目标，以及 IDE 已经登录过的服务。

真正有意思的不是“在 IDEA 里跑 Kotlin”。真正有意思的是，Agent 终于可以看见代码的语法树形状，渐进式展开每个节点背后的语义信息，并且活用 IDE 自己的能力完成精确的局部编辑与重构。

它不必把整个文件塞进 prompt，然后靠模型硬记结构。它可以先观察程序的形状：声明、表达式、引用、作用域、诊断、跳转目标。需要更多信息时，再按需展开下一层。这让代码理解不再像文本预测，而更像真的在一个活着的开发环境里工作。

## Agent 实际会执行什么

先看光标附近的语法树形状，而不是一上来读完整文件：

```kotlin
val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: error("no editor")
val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: error("no PSI")
val element = file.findElementAt(editor.caretModel.offset) ?: error("no element")
generateSequence(element) { it.parent }
    .take(8)
    .joinToString("\n") { "${it.javaClass.simpleName}: ${it.text.take(80)}" }
```

返回值：

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

Agent 得到的是一条紧凑的语法树路径：名称引用、调用表达式、属性、代码块、lambda，以及外层 server builder 调用。它可以先根据结构决定下一步要问什么，而不是直接在原始文本里猜。

像 Command-click 一样解析符号：

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

返回值：

```text
jar:///Users/stream/.gradle/caches/modules-2/files-2.1/io.modelcontextprotocol/kotlin-sdk-server-jvm/0.12.0/.../kotlin-sdk-server-jvm-0.12.0-sources.jar!/commonMain/io/modelcontextprotocol/kotlin/sdk/server/Server.kt
( protected val serverInfo: Implementation, protected val options: ServerOptions, protected val instructionsProvider: (() -> String)? = null, block: Server.() -> Unit = {}, )
```

Agent 得到的是真正的声明位置、依赖源码，或者 IDEA 的反编译视图。库代码不再是黑箱。

询问 IDEA：这里它会建议什么？

```kotlin
val actions = readAction {
    ShowIntentionsPass.getActionsToShow(editor, file)
}
actions.intentionsToShow.joinToString("\n") { it.action.text }
```

返回值：

```text
intentionsToShow:
- Remove explicit type specification
- Split property declaration
errorFixesToShow:
inspectionFixesToShow:
```

Agent 得到的是开发者在灯泡里看到的同一组动作：quick fix、intention、inspection fix，以及局部清理建议。

把代码补全当作本地事实来源：

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

返回值：

```text
Rename (⇧F6)
Rename (⇧F6)
Surround with 'try / finally' (⌥⌘T)
Surround with 'try / finally' (⌥⌘T)
Surround with 'try / catch / finally' (⌥⌘T)
Surround with 'try / catch / finally' (⌥⌘T)
```

Agent 看到的是 IDEA completion pipeline 的真实候选。根据光标位置不同，这些候选可能是符号、关键字、live template，也可能是这种 command-style completion。

询问 Search Everywhere：IDEA 现在有哪些可搜索入口？

```kotlin
val contributors = withContext(Dispatchers.EDT) {
    SearchEverywhereManagerImpl.createContributors(event, project, true)
}
contributors.joinToString("\n") {
    "${it.searchProviderId}: ${it.groupName}"
}
```

返回值：

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

Agent 可以使用开发者按下 Search Everywhere 时看到的同一组发现入口：文件、类、符号、动作、端点、运行配置和 Git。

## 为什么它不一样

- Agent 可以像你 Command-click 一样追踪符号。
- Agent 可以先观察语法树形状，而不是一上来读完整文件。
- Agent 可以按需加载更深一层的语义上下文。
- Agent 可以把 Search Everywhere 当成 IDE 里“下一步去哪找”的地图。
- Agent 可以看到编辑器灯泡里出现的 quick fixes。
- Agent 可以把代码补全当成本地 API 真相来源，而不是猜。
- Agent 可以等 Gradle sync 完成后再分析未解析代码。
- Agent 可以复用 IDEA 已经登录的 GitHub 账号，而不是再要一个 token。
- Agent 可以在多轮调用之间保留 REPL 状态，让探索变成连续对话，而不是一堆一次性命令。

## 使用场景

### 先看形状，再读细节

大文件最难的不是“读不完”，而是看不清结构。

Agent 可以从 PSI 父链开始，而不是从整文件文本开始。它先知道光标是在调用参数、lambda body、属性初始化、import，还是类声明里。然后它再决定下一层要展开什么：同一个 block 里的兄弟节点、所在函数签名、外层 class，或者同一个符号的引用。

这会改变编辑行为。局部编辑之所以能保持局部，是因为 Agent 知道自己碰到的表达式边界在哪里。重构之所以能交给 IDE，是因为 Agent 知道这个节点到底是声明、引用，还是 package 边界。

### 跟随代码，而不是搜索文本

你可以让 Agent 解释一个调用最终会走到哪里。

它不需要只靠 grep 搜文件名和字符串，而是可以从光标下的 PSI 元素出发，解析引用，并询问 IDEA 对应的 navigation element。如果目标在依赖里，IDEA 仍然可以把它带到 attached sources 或反编译视图。如果目标来自生成代码或索引，IDEA 也知道哪里才是有意义的表示。

结果不是“匹配字符串列表”，而是一条和 Command-click 相同的语义路径。Agent 可以拿到真实声明，查看参数，再决定是否继续向上游追。

### 搜索 IDE，而不只是搜索仓库

Search Everywhere 的价值在于，它不是一个搜索框，而是很多 IDE 搜索面板背后统一的入口。

Agent 可以先询问 IDEA 当前有哪些 contributor，然后选择正确的地方搜索：找路径时搜 Files，找 API 时搜 Symbols，找命令时搜 Actions，找分支或提交时搜 Git，找路由时搜 Endpoints，找可运行目标时搜 Run Configurations。

这和 `rg` 是完全不同的起点。Agent 可以通过 IDE 自己的导航模型发现 “Share Project on GitHub”、“Gradle sync”、“Run Configurations” 或某个符号名，然后再调用对应的 IntelliJ API。

### 让 IDEA 告诉 Agent 下一步能做什么

当代码出现警告时，Agent 可以读取你在编辑器里看到的同一组上下文动作。

这意味着它可以在动手编辑前先读取灯泡菜单。它可以区分 intention、error fix、inspection fix，把当前可选动作展示出来，再选择最符合目标的那一个。对于简单清理，IDEA 可能已经知道最安全的变换。对于模糊修复，action 列表至少给了 Agent 一个有依据的菜单，而不是一片空白。

这对 review 也很有用。Agent 甚至可以人为制造一个很小的未解析或可疑结构，询问 IDEA 会给什么 quick fix，然后把这个平台级修法迁移到真实修改上。

### 用本地补全理解本地代码

代码补全是一个非常强的本地事实来源。

当 Agent 不确定当前项目、当前 IDE 版本、当前插件、当前依赖图、当前 source set 里到底有哪些 API 可用时，它可以直接询问 IDEA 在当前位置会补全什么。这个答案天然带着项目上下文、语言上下文、classpath 和当前半成品代码。

有时返回的是成员或扩展函数。有时返回的是关键字、live template，或者 Rename、Surround With 这种 command-style completion。不管是哪一种，Agent 都知道 IDEA 在这个光标位置认为哪些下一步是合法的。

### 在 IDE 的注视下重构

用文本替换重命名 package 很脆。

通过 IDEA，Agent 可以先检查 PSI 节点，确认自己碰到的是声明、package directive、class、function，还是 reference。然后它再调用理解 import、Kotlin/Java PSI、引用关系和项目结构的重构能力。

在 rename 之前，它可以先问 IDEA 这次会影响哪些引用：

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

返回值：

```text
declaration=internal const val KOTLIN_EVAL_TOOL_NAME = "kotlin_eval"
references=1
- src/main/kotlin/io/github/stream29/idea/kts/mcp/IdeaKtsReplMcpServerService.kt:127 KOTLIN_EVAL_TOOL_NAME
```

关键变化是，Agent 不需要用“改很多文件”来模拟重构。它可以要求 IDEA 执行语义操作，再检查变更结果。追引用交给 IDE，Agent 保留意图和验证循环。

### 在同一个地方构建、同步、诊断

没有 Gradle sync，很多 PSI 分析都会半失明。

Agent 可以通过 IDEA 的 External System 集成触发 Gradle sync 或 Gradle task，然后在同一个项目模型里观察结果。sync 完成后，索引、依赖、source set、生成源码和诊断信息才会和开发者眼中的项目对齐。

它也可以直接询问 IDEA 当前导入的 Gradle 项目模型：

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

返回值：

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

这让构建反馈和语义分析不再是两套割裂的世界。Agent 可以 sync、等待、重新读取 PSI、查看 highlighting 或 problems，然后再决定是否需要改代码。

### 把 IDE 当作可信桌面

如果 IDEA 里已经登录了你的 GitHub 账号，Agent 就可以使用这份 IDE 里的认证能力。

例如，它可以通过 IDEA 的 Git 和 GitHub 插件创建公开仓库、配置项目 remote，并推送当前分支，而不需要向你索要额外 token。

在真正操作前，它可以先验证 IDE 里的可信上下文：

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

返回值：

```text
githubAccount=Stream29@github.com
tokenAvailable=true
gitRepositories=1
remote.origin=[https://github.com/Stream29/IdeaKtsReplMcp.git]
```

这不只适用于 GitHub。更大的模式是：IDE 本来就是可信桌面上下文。账号、VCS roots、运行配置、Gradle 项目、打开的编辑器都已经在那里。Agent 可以直接使用这些上下文，而不是从零重建。

## 工具

插件暴露一个 MCP 工具：

- `kotlin_eval`
  - 在 IntelliJ IDEA 内部执行 Kotlin 脚本。
  - 按打开的项目保留 REPL 状态。
  - 以 `project` 作为主要入口。
  - 返回脚本最后一个表达式的值。
  - 不把 stdout 当作回答通道。

工具描述里已经内置了实用脚本范式，包括 PSI 读取、smart read action、write command、Search Everywhere、context action、代码补全、Gradle、符号导航，以及长任务异步轮询。

## 设置

打开 `Settings | Tools | IdeaKtsReplMcp`。

你可以在这里：

- 启用或禁用 MCP server。
- 配置 bind host。
- 配置起始端口。

## 安全

这是 IDE 进程内部的无限制代码执行能力。

除非你明确知道自己要暴露 IDE，否则请保持 localhost 绑定。脚本可以访问项目文件、本地文件、IDE 服务、IDE 集成可用的凭据，以及任何 IntelliJ Platform API 能触达的东西。
