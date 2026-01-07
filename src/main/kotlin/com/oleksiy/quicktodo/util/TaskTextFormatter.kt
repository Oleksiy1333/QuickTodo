package com.oleksiy.quicktodo.util

import com.oleksiy.quicktodo.model.Task

/**
 * Utility for formatting task text for various uses (clipboard, Claude commands, etc.)
 */
object TaskTextFormatter {

    /**
     * Formats a task with all its subtasks and descriptions in a readable format.
     * @param task The task to format
     * @return Formatted string representation of the task hierarchy
     */
    fun formatTaskWithSubtasks(task: Task): String {
        val sb = StringBuilder()
        sb.append(task.text)

        // Include description if present (indented below task name)
        if (task.hasDescription()) {
            sb.append("\n")
            task.description.lines().forEach { line ->
                sb.append("  $line\n")
            }
        }

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

            // Include subtask description if present
            if (subtask.hasDescription()) {
                subtask.description.lines().forEach { line ->
                    sb.append("\n$indent    $line")
                }
            }

            if (subtask.subtasks.isNotEmpty()) {
                appendSubtasks(sb, subtask.subtasks, depth + 1)
            }
        }
    }

    /**
     * Escapes text for safe use in shell commands.
     * @param text The text to escape
     * @return Shell-safe escaped string
     */
    fun escapeForShell(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
    }
}
