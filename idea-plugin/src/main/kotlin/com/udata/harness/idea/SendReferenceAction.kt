package com.udata.harness.idea

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

open class SendReferenceAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null &&
            (event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.isNotEmpty() == true ||
                event.getData(CommonDataKeys.VIRTUAL_FILE) != null)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // Use the popup's target, which may differ from the active editor tab.
        val target = event.getData(CommonDataKeys.VIRTUAL_FILE)
        // Keyboard actions have a different place from editor popup actions.
        // Keep tab/project popups bound to their target; shortcuts use the focused editor.
        val editor = if (event.place == ActionPlaces.PROJECT_VIEW_POPUP ||
            event.place == ActionPlaces.EDITOR_TAB_POPUP) null else event.getData(CommonDataKeys.EDITOR)
        val files = if (editor == null && event.place != ActionPlaces.EDITOR_TAB_POPUP) {
            event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() }?.toList()
                ?: listOfNotNull(target)
        } else listOfNotNull(target)
        val references = files.mapNotNull { file ->
            HarnessPanel.fileReference(project, file, editor)?.let { reference ->
                val path = reference["path"]
                if (reference.containsKey("startLine")) "@$path#L${reference["startLine"]}-L${reference["endLine"]}"
                else "@$path"
            }
        }.distinct()
        if (references.isEmpty()) {
            Messages.showInfoMessage(project, "只能引用当前项目目录内的文件或目录。", "UData Harness")
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("UData Harness")
        if (toolWindow == null) {
            Messages.showInfoMessage(project, "UData Harness 工具窗口不可用，请重启 IDEA 后重试。", "UData Harness")
            return
        }
        // Materialize lazy tool window content before looking up its panel.
        toolWindow.contentManager
        toolWindow.activate({
            val panel = project.getService(HarnessProjectService::class.java).panel
            if (panel == null) {
                Messages.showInfoMessage(project, "UData Harness 对话框尚未初始化，请打开工具窗口后重试。", "UData Harness")
            } else panel.insertReferences(references)
        }, true)
    }
}
