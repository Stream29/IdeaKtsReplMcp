package io.github.stream29.idea.kts.mcp.script.repl

import java.io.File

data class KotlinReplState(
    val historyCellReceivers: List<Any>,
    val dependsOnClasspath: Set<File>,
    val lastSnippetClassLoader: ClassLoader,
) {
    companion object {
        val Empty = KotlinReplState(
            historyCellReceivers = emptyList(),
            dependsOnClasspath = emptySet(),
            lastSnippetClassLoader = KotlinReplState::class.java.classLoader,
        )
    }
}
