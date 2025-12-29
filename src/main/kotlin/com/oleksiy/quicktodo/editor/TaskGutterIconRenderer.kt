package com.oleksiy.quicktodo.editor

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.ui.ChecklistPanel
import com.oleksiy.quicktodo.ui.QuickTodoIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.Icon

/**
 * Renders a gutter icon for tasks linked to code locations.
 * Clicking the icon navigates to the task in the QuickTodo panel.
 */
class TaskGutterIconRenderer(
    private val task: Task,
    private val project: Project
) : GutterIconRenderer() {

    override fun getIcon(): Icon = QuickTodoIcons.TaskMarker

    override fun getTooltipText(): String = "Task: ${task.text}"

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            navigateToTask()
        }
    }

    private fun navigateToTask() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("QuickTodo")
        toolWindow?.show {
            // After tool window is shown, select the task
            ChecklistPanel.getInstance(project)?.selectTaskById(task.id)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskGutterIconRenderer) return false
        return task.id == other.task.id
    }

    override fun hashCode(): Int = task.id.hashCode()

    override fun getAlignment(): Alignment = Alignment.LEFT
}
