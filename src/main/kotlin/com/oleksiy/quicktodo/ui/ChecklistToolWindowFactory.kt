package com.oleksiy.quicktodo.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import com.oleksiy.quicktodo.action.CollapseAllAction
import com.oleksiy.quicktodo.action.ExpandAllAction
import com.oleksiy.quicktodo.action.ToggleHideCompletedAction

class ChecklistToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val checklistPanel = ChecklistPanel(project)
        val content = ContentFactory.getInstance().createContent(
            checklistPanel.getContent(),
            "",
            false
        )
        // Register the panel for disposal when the content is removed
        content.setDisposer(checklistPanel)
        toolWindow.contentManager.addContent(content)

        // Add Expand/Collapse/Hide actions to title bar
        (toolWindow as? ToolWindowEx)?.setTitleActions(listOf(
            ExpandAllAction(checklistPanel),
            CollapseAllAction(checklistPanel),
            ToggleHideCompletedAction(checklistPanel)
        ))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
