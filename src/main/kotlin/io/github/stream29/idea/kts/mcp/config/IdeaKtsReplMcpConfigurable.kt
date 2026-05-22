package io.github.stream29.idea.kts.mcp.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class IdeaKtsReplMcpConfigurable : Configurable {
    private val settings: IdeaKtsReplMcpSettings
        get() = ApplicationManager.getApplication().getService(IdeaKtsReplMcpSettings::class.java)

    private var enabledField: JBCheckBox? = null
    private var hostField: JBTextField? = null
    private var portField: JBTextField? = null
    private var scriptTimeoutMsField: JBTextField? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "IdeaKtsReplMcp"

    override fun createComponent(): JComponent {
        enabledField = JBCheckBox("Enable MCP server")
        hostField = JBTextField()
        portField = JBTextField()
        scriptTimeoutMsField = JBTextField()

        panel = FormBuilder.createFormBuilder()
            .addComponent(enabledField!!)
            .addLabeledComponent(JBLabel("Host:"), hostField!!, 1, false)
            .addLabeledComponent(JBLabel("Port:"), portField!!, 1, false)
            .addLabeledComponent(JBLabel("Script timeout (ms):"), scriptTimeoutMsField!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = settings.currentState
        return enabledField?.isSelected != state.enabled ||
                hostField?.text.orEmpty().trim() != state.host ||
                portField?.text.orEmpty().trim() != state.port.toString() ||
                scriptTimeoutMsField?.text.orEmpty().trim() != state.scriptTimeoutMs.toString()
    }

    override fun apply() {
        val port = portField?.text.orEmpty().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            throw ConfigurationException("Port must be a number between 1 and 65535.")
        }

        val host = hostField?.text.orEmpty().trim()
        if (host.isBlank()) {
            throw ConfigurationException("Host must not be empty.")
        }

        val scriptTimeoutMs = scriptTimeoutMsField?.text.orEmpty().trim().toLongOrNull()
        if (scriptTimeoutMs == null || scriptTimeoutMs <= 0) {
            throw ConfigurationException("Script timeout must be a positive number.")
        }

        settings.updateState(
            enabled = enabledField?.isSelected == true,
            host = host,
            port = port,
            scriptTimeoutMs = scriptTimeoutMs,
        )
    }

    override fun reset() {
        val state = settings.currentState
        enabledField?.isSelected = state.enabled
        hostField?.text = state.host
        portField?.text = state.port.toString()
        scriptTimeoutMsField?.text = state.scriptTimeoutMs.toString()
    }

    override fun disposeUIResources() {
        enabledField = null
        hostField = null
        portField = null
        scriptTimeoutMsField = null
        panel = null
    }
}
