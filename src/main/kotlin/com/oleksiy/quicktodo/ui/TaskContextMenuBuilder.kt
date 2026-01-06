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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.oleksiy.quicktodo.util.ClaudeCodePluginChecker
import com.oleksiy.quicktodo.util.TerminalCommandRunner
import javax.swing.JPopupMenu

/**
 * Builds context menus for tasks in the checklist tree.
 */
class TaskContextMenuBuilder(
    private val project: Project,
    private val taskService: TaskService,
    private val focusService: FocusService,
    private val onEditTask: (Task) -> Unit,
    private val onAddSubtask: (Task) -> Unit
) {
    companion object {
        private fun formatTaskWithSubtasks(task: Task): String {
            val sb = StringBuilder()
            sb.append(task.text)
            if (task.subtasks.isNotEmpty()) {
                sb.append("\n\nSubtasks:")
                appendSubtasks(sb, task.subtasks, 1)
            }
            return sb.toString()
        }

        private fun appendSubtasks(sb: StringBuilder, subtasks: List<Task>, depth: Int) {
            val indent = "  ".repeat(depth)
            for (subtask in subtasks) {
                val status = if (subtask.isCompleted) "[x]" else "[ ]"
                sb.append("\n$indent- $status ${subtask.text}")
                if (subtask.subtasks.isNotEmpty()) {
                    appendSubtasks(sb, subtask.subtasks, depth + 1)
                }
            }
        }

        private fun escapeForShell(text: String): String {
            return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`")
        }
    }

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
            actionGroup.add(object : AnAction("Add Subtask", "Add a subtask to this task", QuickTodoIcons.AddSubtask) {
                override fun actionPerformed(e: AnActionEvent) = onAddSubtask(task)
            })
        }

        // Edit action
        actionGroup.add(object : AnAction("Edit Task", "Edit task text", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) = onEditTask(task)
        })

        // Edit Time action
        actionGroup.add(object : AnAction("Edit Time", "Edit task time", AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) {
                val dialog = EditTimeDialog(project, task.text, task.ownTimeSpentMs)
                if (dialog.showAndGet()) {
                    val newTimeMs = dialog.getTimeMs()
                    if (newTimeMs != null) {
                        taskService.updateTaskOwnTime(task.id, newTimeMs)
                    } else {
                        Messages.showErrorDialog(project, "Invalid time format", "Edit Time")
                    }
                }
            }
        })

        // Claude Code integration - show if Claude Code plugin is installed and Terminal is available
        if (ClaudeCodePluginChecker.isClaudeCodeInstalled() && TerminalCommandRunner.isTerminalAvailable()) {
            actionGroup.add(Separator.getInstance())
            actionGroup.add(object : AnAction(
                "Plan with Claude",
                "Open Claude Code in plan mode (read-only)",
                QuickTodoIcons.Claude
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    val taskText = escapeForShell(formatTaskWithSubtasks(task))
                    val command = "claude --permission-mode plan \"$taskText\""
                    if (!TerminalCommandRunner.executeCommand(project, command, "Claude Code")) {
                        Messages.showWarningDialog(project, "Failed to open terminal for Claude Code", "Claude Code")
                    }
                }
            })
            actionGroup.add(object : AnAction(
                "Implement with Claude",
                "Open Claude Code to implement this task",
                QuickTodoIcons.Claude
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    val taskText = escapeForShell(formatTaskWithSubtasks(task))
                    val command = "claude \"$taskText\""
                    if (!TerminalCommandRunner.executeCommand(project, command, "Claude Code")) {
                        Messages.showWarningDialog(project, "Failed to open terminal for Claude Code", "Claude Code")
                    }
                }
            })
            actionGroup.add(Separator.getInstance())
        }

        // Location actions
        if (task.hasCodeLocation()) {
            // Clear location if task already has one
            actionGroup.add(object : AnAction(
                "Clear Linked Location",
                "Remove the linked code location from this task",
                AllIcons.Actions.Cancel
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    taskService.setTaskLocation(task.id, null)
                }
            })
        } else {
            // Link current location
            actionGroup.add(object : AnAction(
                "Link Current Location",
                "Attach current cursor position to this task",
                AllIcons.Actions.AddFile
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    val location = CodeLocationUtil.captureCurrentLocation(project)
                    if (location != null) {
                        taskService.setTaskLocation(task.id, location)
                    } else {
                        Messages.showWarningDialog(
                            project,
                            "No file is currently open in the editor. Please open a file first.",
                            "Cannot Link Location"
                        )
                    }
                }
            })
        }

        // Copy action
        actionGroup.add(object : AnAction("Copy", "Copy task name to clipboard", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val text = allSelectedTasks.joinToString("\n") { it.text }
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })

        // Delete action
        val deleteLabel = if (allSelectedTasks.size > 1) "Delete ${allSelectedTasks.size} Tasks" else "Delete Task"
        actionGroup.add(object : AnAction(deleteLabel, "Delete selected task(s)", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                allSelectedTasks.forEach { focusService.onTaskDeleted(it.id) }
                if (allSelectedTasks.size == 1) {
                    taskService.removeTask(task.id)
                } else {
                    taskService.removeTasks(allSelectedTasks.map { it.id })
                }
            }
        })

        return ActionManager.getInstance()
            .createActionPopupMenu("TaskContextMenu", actionGroup)
            .component
    }
}
