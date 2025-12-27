package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.action.SetPriorityAction
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import javax.swing.JPopupMenu

/**
 * Builds context menus for tasks in the checklist tree.
 */
class TaskContextMenuBuilder(
    private val taskService: TaskService,
    private val onEditTask: (Task) -> Unit
) {

    /**
     * Creates a context menu for the given task.
     */
    fun buildContextMenu(task: Task): JPopupMenu {
        val actionGroup = DefaultActionGroup()

        // Priority submenu
        val priorityGroup = DefaultActionGroup("Set Priority", true).apply {
            templatePresentation.icon = QuickTodoIcons.getIconForPriority(Priority.HIGH)
        }

        Priority.entries.forEach { priority ->
            priorityGroup.add(SetPriorityAction(priority, { task }, taskService))
        }

        actionGroup.add(priorityGroup)
        actionGroup.add(Separator.getInstance())

        // Edit action
        actionGroup.add(object : AnAction("Edit Task", "Edit task text", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) = onEditTask(task)
        })

        // Delete action
        actionGroup.add(object : AnAction("Delete Task", "Delete this task", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                taskService.removeTask(task.id)
            }
        })

        return ActionManager.getInstance()
            .createActionPopupMenu("TaskContextMenu", actionGroup)
            .component
    }
}
