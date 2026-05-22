package io.github.stream29.idea.kts.mcp.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.github.stream29.idea.kts.mcp.KOTLIN_EVAL_DEFAULT_TIMEOUT_MS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.APP)
@State(name = "IdeaKtsReplMcpSettings", storages = [Storage("ideaKtsReplMcp.xml")])
class IdeaKtsReplMcpSettings : PersistentStateComponent<IdeaKtsReplMcpSettings.State> {
    private val mutableState = MutableStateFlow(State())

    val currentState: State
        get() = mutableState.value

    val stateFlow: StateFlow<State> = mutableState.asStateFlow()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        mutableState.value = state.copy()
    }

    fun updateState(enabled: Boolean, host: String, port: Int, scriptTimeoutMs: Long) {
        mutableState.value = State(
            enabled = enabled,
            host = host,
            port = port,
            scriptTimeoutMs = scriptTimeoutMs,
        )
    }

    data class State(
        var enabled: Boolean = true,
        var host: String = "127.0.0.1",
        var port: Int = 39393,
        var scriptTimeoutMs: Long = KOTLIN_EVAL_DEFAULT_TIMEOUT_MS,
    )
}
