package com.oleksiy.quicktodo.ui.handler

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.NewTaskDialog
import com.oleksiy.quicktodo.ui.TaskTreeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * Handles adding new tasks and subtasks.
 */
class AddTaskHandler(
    private val project: Project,
    private val taskService: TaskService,
    private val treeManager: TaskTreeManager,
    private val getSelectedTask: () -> Task?
) {
    /**
     * Adds a new task. If a task is selected and can have subtasks, adds as subtask.
     * Otherwise adds as root task.
     */
    fun addTask() {
        val selectedTask = getSelectedTask()

        if (selectedTask != null) {
            if (selectedTask.canAddSubtask()) {
                addSubtaskTo(selectedTask, keepParentSelected = true)
                return
            } else {
                // Max nesting reached - warn user and offer to add as root
                val result = Messages.showYesNoDialog(
                    project,
                    "Maximum nesting level (3) reached. Add as a new root task instead?",
                    "Cannot Add Subtask",
                    Messages.getQuestionIcon()
                )
                if (result != Messages.YES) {
                    return
                }
                // Fall through to add as root task
            }
        }

        addRootTask()
    }

    /**
     * Adds a subtask to the currently selected task.
     */
    fun addSubtask() {
        val selectedTask = getSelectedTask() ?: return

        if (!selectedTask.canAddSubtask()) {
            Messages.showWarningDialog(
                project,
                "Maximum nesting level (3) reached. Cannot add more subtasks.",
                "Cannot Add Subtask"
            )
            return
        }

        addSubtaskTo(selectedTask, keepParentSelected = false)
    }

    /**
     * Adds a subtask to a specific task.
     */
    fun addSubtaskToTask(task: Task) {
        treeManager.selectTaskById(task.id)
        addSubtask()
    }

    private fun addSubtaskTo(parentTask: Task, keepParentSelected: Boolean) {
        treeManager.ensureTaskExpanded(parentTask.id)
        val dialog = NewTaskDialog(project, "New Subtask")
        if (dialog.showAndGet()) {
            val text = dialog.getTaskText()
            val description = dialog.getDescription()
            val priority = dialog.getSelectedPriority()
            val location = dialog.getCodeLocation()
            if (text.isNotBlank()) {
                val subtask = taskService.addSubtask(parentTask.id, text, priority)
                if (subtask != null) {
                    if (description.isNotBlank()) {
                        taskService.updateTaskDescription(subtask.id, description)
                    }
                    location?.let { taskService.setTaskLocation(subtask.id, it) }
                    SwingUtilities.invokeLater {
                        if (keepParentSelected) {
                            treeManager.selectTaskById(parentTask.id)
                            treeManager.scrollToTaskById(subtask.id)
                        } else {
                            treeManager.selectTaskById(subtask.id)
                        }
                    }
                }
            }
        }
    }

    private fun addRootTask() {
        val dialog = NewTaskDialog(project)
        if (dialog.showAndGet()) {
            val text = dialog.getTaskText()
            val description = dialog.getDescription()
            val priority = dialog.getSelectedPriority()
            val location = dialog.getCodeLocation()
            if (text.isNotBlank()) {
                val task = taskService.addTask(text, priority)
                if (description.isNotBlank()) {
                    taskService.updateTaskDescription(task.id, description)
                }
                location?.let { taskService.setTaskLocation(task.id, it) }
                // Scroll to newly added task without selecting it
                SwingUtilities.invokeLater {
                    treeManager.scrollToTaskById(task.id)
                }
            }
        }
    }
}
