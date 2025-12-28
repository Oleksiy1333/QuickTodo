package com.oleksiy.quicktodo.undo

import com.oleksiy.quicktodo.model.CodeLocation
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task

/**
 * Interface defining operations that commands can execute without creating new undo entries.
 * Implemented by TaskService to avoid circular dependencies and prevent infinite recursion.
 */
interface CommandExecutor {
    // Task creation without undo registration
    fun addTaskWithoutUndo(taskId: String, text: String, priority: Priority): Task
    fun addSubtaskWithoutUndo(subtaskId: String, parentId: String, text: String, priority: Priority): Task?

    // Task removal without undo registration
    fun removeTaskWithoutUndo(taskId: String): Boolean

    // Task updates without undo registration
    fun updateTaskTextWithoutUndo(taskId: String, newText: String): Boolean
    fun setTaskCompletionWithoutUndo(taskId: String, completed: Boolean): Boolean
    fun setTaskPriorityWithoutUndo(taskId: String, priority: Priority): Boolean
    fun setTaskLocationWithoutUndo(taskId: String, location: CodeLocation?): Boolean

    // Task movement without undo registration
    fun moveTaskWithoutUndo(taskId: String, targetParentId: String?, targetIndex: Int): Boolean
    fun moveTasksWithoutUndo(taskIds: List<String>, targetParentId: String?, targetIndex: Int): Boolean

    // Bulk operations without undo registration
    fun clearCompletedTasksWithoutUndo(): Int

    // State restoration (used by undo)
    fun restoreTask(taskSnapshot: Task, parentId: String?, index: Int)
    fun restoreCompletionStates(states: Map<String, Boolean>)
}
