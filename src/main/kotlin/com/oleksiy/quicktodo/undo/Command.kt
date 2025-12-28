package com.oleksiy.quicktodo.undo

import com.oleksiy.quicktodo.model.CodeLocation
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task

/**
 * Sealed interface for all undoable commands.
 * Each command captures the state needed to undo and redo the operation.
 */
sealed interface Command {
    /** Human-readable description for potential UI display */
    val description: String

    /** Execute the undo operation */
    fun undo(executor: CommandExecutor)

    /** Execute the redo operation (re-apply the original action) */
    fun redo(executor: CommandExecutor)
}

/**
 * Command for adding a new root-level task.
 */
data class AddTaskCommand(
    val taskId: String,
    val text: String,
    val priority: Priority
) : Command {
    override val description: String
        get() = "Add task"

    override fun undo(executor: CommandExecutor) {
        executor.removeTaskWithoutUndo(taskId)
    }

    override fun redo(executor: CommandExecutor) {
        executor.addTaskWithoutUndo(taskId, text, priority)
    }
}

/**
 * Command for adding a subtask to an existing task.
 */
data class AddSubtaskCommand(
    val subtaskId: String,
    val parentId: String,
    val text: String,
    val priority: Priority
) : Command {
    override val description: String
        get() = "Add subtask"

    override fun undo(executor: CommandExecutor) {
        executor.removeTaskWithoutUndo(subtaskId)
    }

    override fun redo(executor: CommandExecutor) {
        executor.addSubtaskWithoutUndo(subtaskId, parentId, text, priority)
    }
}

/**
 * Command for removing a task (with all its subtasks).
 * Captures full task snapshot for restoration.
 */
data class RemoveTaskCommand(
    val taskSnapshot: Task,
    val parentId: String?,
    val index: Int
) : Command {
    override val description: String
        get() = "Delete task"

    override fun undo(executor: CommandExecutor) {
        executor.restoreTask(taskSnapshot, parentId, index)
    }

    override fun redo(executor: CommandExecutor) {
        executor.removeTaskWithoutUndo(taskSnapshot.id)
    }
}

/**
 * Command for editing task text.
 */
data class EditTaskTextCommand(
    val taskId: String,
    val oldText: String,
    val newText: String
) : Command {
    override val description: String
        get() = "Edit task"

    override fun undo(executor: CommandExecutor) {
        executor.updateTaskTextWithoutUndo(taskId, oldText)
    }

    override fun redo(executor: CommandExecutor) {
        executor.updateTaskTextWithoutUndo(taskId, newText)
    }
}

/**
 * Command for changing task completion status.
 * Captures completion states of task and all subtasks for cascade operations.
 */
data class SetTaskCompletionCommand(
    val taskId: String,
    val completionStates: Map<String, Boolean>,
    val newCompleted: Boolean
) : Command {
    override val description: String
        get() = if (newCompleted) "Complete task" else "Uncomplete task"

    override fun undo(executor: CommandExecutor) {
        executor.restoreCompletionStates(completionStates)
    }

    override fun redo(executor: CommandExecutor) {
        executor.setTaskCompletionWithoutUndo(taskId, newCompleted)
    }
}

/**
 * Command for changing task priority.
 */
data class SetTaskPriorityCommand(
    val taskId: String,
    val oldPriority: Priority,
    val newPriority: Priority
) : Command {
    override val description: String
        get() = "Change priority"

    override fun undo(executor: CommandExecutor) {
        executor.setTaskPriorityWithoutUndo(taskId, oldPriority)
    }

    override fun redo(executor: CommandExecutor) {
        executor.setTaskPriorityWithoutUndo(taskId, newPriority)
    }
}

/**
 * Command for moving tasks (single or multiple).
 * Captures original positions of all moved tasks.
 */
data class MoveTasksCommand(
    val taskMoves: List<TaskMoveInfo>,
    val targetParentId: String?,
    val targetIndex: Int
) : Command {

    data class TaskMoveInfo(
        val taskId: String,
        val originalParentId: String?,
        val originalIndex: Int
    )

    override val description: String
        get() = if (taskMoves.size == 1) "Move task" else "Move ${taskMoves.size} tasks"

    override fun undo(executor: CommandExecutor) {
        // Restore in reverse order to maintain correct indices
        taskMoves.reversed().forEach { move ->
            executor.moveTaskWithoutUndo(move.taskId, move.originalParentId, move.originalIndex)
        }
    }

    override fun redo(executor: CommandExecutor) {
        executor.moveTasksWithoutUndo(taskMoves.map { it.taskId }, targetParentId, targetIndex)
    }
}

/**
 * Command for clearing all completed tasks (bulk delete).
 * Captures snapshots of all removed tasks with their positions.
 */
data class ClearCompletedTasksCommand(
    val removedTasks: List<RemovedTaskInfo>
) : Command {

    data class RemovedTaskInfo(
        val taskSnapshot: Task,
        val parentId: String?,
        val index: Int
    )

    override val description: String
        get() = "Clear ${removedTasks.size} completed"

    override fun undo(executor: CommandExecutor) {
        // Restore in original order (by index) to maintain correct positions
        // Sort by parentId (nulls first for root), then by index
        removedTasks
            .sortedWith(compareBy({ it.parentId != null }, { it.index }))
            .forEach { info ->
                executor.restoreTask(info.taskSnapshot, info.parentId, info.index)
            }
    }

    override fun redo(executor: CommandExecutor) {
        executor.clearCompletedTasksWithoutUndo()
    }
}

/**
 * Command for setting or clearing task code location.
 */
data class SetTaskLocationCommand(
    val taskId: String,
    val oldLocation: CodeLocation?,
    val newLocation: CodeLocation?
) : Command {
    override val description: String
        get() = when {
            oldLocation == null && newLocation != null -> "Attach location"
            oldLocation != null && newLocation == null -> "Remove location"
            else -> "Update location"
        }

    override fun undo(executor: CommandExecutor) {
        executor.setTaskLocationWithoutUndo(taskId, oldLocation)
    }

    override fun redo(executor: CommandExecutor) {
        executor.setTaskLocationWithoutUndo(taskId, newLocation)
    }
}
