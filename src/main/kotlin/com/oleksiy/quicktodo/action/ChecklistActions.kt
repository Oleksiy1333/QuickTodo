package com.oleksiy.quicktodo.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.QuickTodoIcons

/**
 * Callback interface for checklist panel operations.
 * Allows action classes to interact with the panel without tight coupling.
 */
interface ChecklistActionCallback {
    fun getSelectedTask(): Task?
    fun addSubtask()
    fun canMoveSelectedTask(direction: Int): Boolean
    fun moveSelectedTask(direction: Int)
    fun expandAll()
    fun collapseAll()
    fun hasCompletedTasks(): Boolean
    fun clearCompletedTasks()
    fun isHideCompletedEnabled(): Boolean
    fun toggleHideCompleted()
}

/**
 * Action to add a subtask to the currently selected task.
 */
class AddSubtaskAction(
    private val callback: ChecklistActionCallback
) : AnAction("Add Subtask", "Add a subtask to selected task", AllIcons.General.Add) {

    override fun actionPerformed(e: AnActionEvent) {
        callback.addSubtask()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = callback.getSelectedTask()?.canAddSubtask() == true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/**
 * Action to move the selected task up or down in its sibling list.
 */
class MoveTaskAction(
    private val direction: Int,
    private val callback: ChecklistActionCallback
) : AnAction(
    if (direction < 0) "Move Up" else "Move Down",
    if (direction < 0) "Move selected task up" else "Move selected task down",
    if (direction < 0) AllIcons.Actions.MoveUp else AllIcons.Actions.MoveDown
) {
    override fun actionPerformed(e: AnActionEvent) {
        callback.moveSelectedTask(direction)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = callback.canMoveSelectedTask(direction)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/**
 * Action to expand all nodes in the task tree.
 */
class ExpandAllAction(
    private val callback: ChecklistActionCallback
) : AnAction("Expand All", "Expand all tasks", AllIcons.Actions.Expandall) {

    override fun actionPerformed(e: AnActionEvent) {
        callback.expandAll()
    }
}

/**
 * Action to collapse all nodes in the task tree.
 */
class CollapseAllAction(
    private val callback: ChecklistActionCallback
) : AnAction("Collapse All", "Collapse all tasks", AllIcons.Actions.Collapseall) {

    override fun actionPerformed(e: AnActionEvent) {
        callback.collapseAll()
    }
}

/**
 * Action to remove all completed tasks from the list.
 */
class ClearCompletedAction(
    private val callback: ChecklistActionCallback
) : AnAction("Clear Completed", "Remove all completed tasks", AllIcons.Actions.GC) {

    override fun actionPerformed(e: AnActionEvent) {
        callback.clearCompletedTasks()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = callback.hasCompletedTasks()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/**
 * Action to toggle visibility of completed tasks.
 */
class ToggleHideCompletedAction(
    private val callback: ChecklistActionCallback
) : AnAction(), Toggleable {

    override fun actionPerformed(e: AnActionEvent) {
        callback.toggleHideCompleted()
    }

    override fun update(e: AnActionEvent) {
        val isHidden = callback.isHideCompletedEnabled()
        e.presentation.text = if (isHidden) "Show Completed" else "Hide Completed"
        e.presentation.description = if (isHidden) "Show completed tasks" else "Hide completed tasks"
        e.presentation.icon = if (isHidden) AllIcons.Actions.Show else AllIcons.Actions.ToggleVisibility
        Toggleable.setSelected(e.presentation, isHidden)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

/**
 * Action to set the priority of a task. Used in the context menu.
 */
class SetPriorityAction(
    private val priority: Priority,
    private val taskProvider: () -> Task?,
    private val taskService: TaskService
) : AnAction(
    priority.displayName,
    "Set priority to ${priority.displayName}",
    QuickTodoIcons.getIconForPriority(priority)
), Toggleable {

    override fun actionPerformed(e: AnActionEvent) {
        taskProvider()?.let { task ->
            taskService.setTaskPriority(task.id, priority)
        }
    }

    override fun update(e: AnActionEvent) {
        val isSelected = taskProvider()?.getPriorityEnum() == priority
        Toggleable.setSelected(e.presentation, isSelected)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
