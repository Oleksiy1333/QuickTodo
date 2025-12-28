package com.oleksiy.quicktodo.action

import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.NewTaskDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class AddTaskAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val taskService = project.getService(TaskService::class.java) ?: return

        val dialog = NewTaskDialog(project)
        if (dialog.showAndGet()) {
            val text = dialog.getTaskText()
            val priority = dialog.getSelectedPriority()
            val location = dialog.getCodeLocation()
            if (text.isNotBlank()) {
                val task = taskService.addTask(text, priority)
                location?.let { taskService.setTaskLocation(task.id, it) }
            }
        }
    }
}
