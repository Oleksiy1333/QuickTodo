package com.oleksiy.quicktodo.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.oleksiy.quicktodo.ui.RecentTasksPopup

class ShowRecentTasksAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RecentTasksPopup(project).show()
    }
}
