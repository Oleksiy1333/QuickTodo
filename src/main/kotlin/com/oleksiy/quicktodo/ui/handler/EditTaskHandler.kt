package com.oleksiy.quicktodo.ui.handler

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.NewTaskDialog
import com.intellij.openapi.project.Project

/**
 * Handles editing existing tasks.
 */
class EditTaskHandler(
    private val project: Project,
    private val taskService: TaskService,
    private val getSelectedTask: () -> Task?
) {
    /**
     * Opens edit dialog for the currently selected task.
     */
    fun editSelectedTask() {
        val selectedTask = getSelectedTask() ?: return
        editTask(selectedTask)
    }

    /**
     * Opens edit dialog for a specific task.
     */
    fun editTask(task: Task) {
        val dialog = NewTaskDialog(
            project,
            dialogTitle = "Edit Task",
            initialText = task.text,
            initialPriority = task.getPriorityEnum(),
            initialLocation = task.codeLocation,
            initialDescription = task.description
        )
        if (dialog.showAndGet()) {
            val newText = dialog.getTaskText()
            val newDescription = dialog.getDescription()
            val newPriority = dialog.getSelectedPriority()
            val newLocation = dialog.getCodeLocation()
            if (newText.isNotBlank()) {
                if (newText != task.text) {
                    taskService.updateTaskText(task.id, newText)
                }
                if (newDescription != task.description) {
                    taskService.updateTaskDescription(task.id, newDescription)
                }
                if (newPriority != task.getPriorityEnum()) {
                    taskService.setTaskPriority(task.id, newPriority)
                }
                // Update location (handles add, update, and remove)
                if (newLocation != task.codeLocation) {
                    taskService.setTaskLocation(task.id, newLocation)
                }
            }
        }
    }
}
