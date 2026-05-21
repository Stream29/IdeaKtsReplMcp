package io.github.stream29.idea.kts.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class IdeaKtsReplMcpStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication()
            .getService(IdeaKtsReplMcpServerService::class.java)
            .registerProject(project)
    }
}
