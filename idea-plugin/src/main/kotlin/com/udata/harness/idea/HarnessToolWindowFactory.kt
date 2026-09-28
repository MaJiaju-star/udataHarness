package com.udata.harness.idea

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.util.Disposer

class HarnessToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HarnessPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        project.getService(HarnessProjectService::class.java).panel = panel
        Disposer.register(panel) {
            val service = project.getService(HarnessProjectService::class.java)
            if (service.panel === panel) service.panel = null
        }
    }
}
