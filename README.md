# IdeaKtsReplMcp

IdeaKtsReplMcp is an IntelliJ IDEA plugin prototype that exposes an unrestricted Kotlin script evaluator over a local MCP HTTP endpoint.

## Run

- Build or run the plugin:

```bash
./gradlew runIde
```

- Open any project in the launched IDE.
- The plugin starts an MCP endpoint:

```text
http://127.0.0.1:39393/mcp
```

## Tool

- `kotlin_eval`
  - Runs Kotlin script inside the IntelliJ process.
  - Keeps Kotlin script state per IntelliJ project.
  - Exposes only `project` through the implicit receiver.
  - Returns only the script's final expression value.
  - Do not use stdout or stderr as tool output.
  - Use IntelliJ Platform APIs directly.
  - Call `ApplicationManager.getApplication().invokeAndWait { ... }` when an operation must run on the IDE thread.
  - Use platform read/write APIs such as `ReadAction` and `WriteCommandAction` around PSI access.
  - Optional arguments:
    - `projectPath`
    - `projectName`
    - `resetState`

## Settings

- Open `Settings | Tools | IdeaKtsReplMcp`.
- Manage whether the MCP server is enabled.
- Configure the bind host and starting port.
- Applying changes restarts the IdeaKtsReplMcp MCP server.

## Example

```bash
curl -s http://127.0.0.1:39393/mcp \
  -H 'content-type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "kotlin_eval",
      "arguments": {
        "script": "project.name + \" @ \" + project.basePath"
      }
    }
  }'
```

## PSI Example

```kotlin
com.intellij.openapi.application.ReadAction.compute<List<String>?, RuntimeException> {
    val cls = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
        "com.example.UserService",
        com.intellij.psi.search.GlobalSearchScope.projectScope(project),
    )
    cls?.methods?.map { it.name }
}
```

## Write Example

```kotlin
val result = java.util.concurrent.atomic.AtomicReference<Result<List<String>>>()

com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
    result.set(runCatching {
        com.intellij.openapi.command.WriteCommandAction.writeCommandAction(project)
            .withName("Add hello method")
            .compute<List<String>, RuntimeException> {
                val cls = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(
                    "com.example.UserService",
                    com.intellij.psi.search.GlobalSearchScope.projectScope(project),
                ) ?: error("class not found")
                val factory = com.intellij.psi.JavaPsiFacade.getElementFactory(project)
                val method = factory.createMethodFromText(
                    "public String hello() { return \"hello\"; }",
                    cls,
                )
                cls.add(method)
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
                cls.methods.map { it.name }
            }
    })
}

result.get().getOrThrow()
```

## Warning

- This is unrestricted code execution inside the IDE process.
- Bind to localhost only unless you are intentionally exposing your IDE.
- Scripts can read and write local files through JVM and IntelliJ APIs.
