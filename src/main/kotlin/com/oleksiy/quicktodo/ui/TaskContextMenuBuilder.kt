package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.action.SetPriorityAction
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.FocusService
import com.oleksiy.quicktodo.service.TaskService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
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
    private val focusService: FocusService,
    private val onEditTask: (Task) -> Unit,
    private val onAddSubtask: (Task) -> Unit
) {

    /**
     * Creates a context menu for the given task(s).
     * @param task The primary task (right-clicked task)
     * @param allSelectedTasks All currently selected tasks (for multi-selection operations like copy)
     */
    fun buildContextMenu(task: Task, allSelectedTasks: List<Task> = listOf(task)): JPopupMenu {
        val actionGroup = DefaultActionGroup()

        // Focus action (only for non-completed tasks)
        if (!task.isCompleted) {
            val isFocused = focusService.isFocused(task.id)
            if (isFocused) {
                actionGroup.add(object : AnAction(
                    "Remove Focus",
                    "Remove focus from this task",
                    QuickTodoIcons.Focus
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        focusService.removeFocus()
                    }
                })
            } else {
                actionGroup.add(object : AnAction(
                    "Set as Focus",
                    "Focus on this task and start timer",
                    QuickTodoIcons.Focus
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        focusService.setFocus(task.id)
                    }
                })
            }
            actionGroup.add(Separator.getInstance())
        }

        // Priority submenu
        val priorityGroup = DefaultActionGroup("Set Priority", true)

        Priority.entries.forEach { priority ->
            priorityGroup.add(SetPriorityAction(priority, { task }, taskService))
        }

        actionGroup.add(priorityGroup)
        actionGroup.add(Separator.getInstance())

        // Add Subtask action (only if nesting level allows)
        if (task.canAddSubtask()) {
            actionGroup.add(object : AnAction("Add Subtask", "Add a subtask to this task", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = onAddSubtask(task)
            })
        }

        // Edit action
        actionGroup.add(object : AnAction("Edit Task", "Edit task text", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) = onEditTask(task)
        })

        // Copy action
        actionGroup.add(object : AnAction("Copy", "Copy task name to clipboard", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val text = allSelectedTasks.joinToString("\n") { it.text }
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })

        // Delete action
        actionGroup.add(object : AnAction("Delete Task", "Delete this task", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                focusService.onTaskDeleted(task.id)
                taskService.removeTask(task.id)
            }
        })

        return ActionManager.getInstance()
            .createActionPopupMenu("TaskContextMenu", actionGroup)
            .component
    }
}
