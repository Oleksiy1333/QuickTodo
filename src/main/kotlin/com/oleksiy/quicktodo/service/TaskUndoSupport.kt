package com.oleksiy.quicktodo.service

import com.oleksiy.quicktodo.model.CodeLocation
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.undo.CommandExecutor
import com.oleksiy.quicktodo.undo.TaskSnapshot

/**
 * Implements CommandExecutor for undo/redo command execution.
 * Delegates task mutations to TaskRepository and notifies listeners.
 *
 * This class is used by UndoRedoManager to execute undo/redo operations
 * without creating new undo entries (which would cause infinite recursion).
 */
internal class TaskUndoSupport(
    private val tasksProvider: () -> MutableList<Task>,
    private val repository: TaskRepository,
    private val notifyListeners: () -> Unit
) : CommandExecutor {

    private val tasks: MutableList<Task> get() = tasksProvider()

    override fun addTaskWithoutUndo(taskId: String, text: String, priority: Priority): Task {
        val task = Task(id = taskId, text = text, level = 0, priority = priority.name, createdAt = System.currentTimeMillis())
        tasks.add(task)
        notifyListeners()
        return task
    }

    override fun addSubtaskWithoutUndo(
        subtaskId: String,
        parentId: String,
        text: String,
        priority: Priority
    ): Task? {
        val parent = tasks.findTaskById(parentId) ?: return null
        if (!parent.canAddSubtask()) return null

        val subtask = Task(
            id = subtaskId,
            text = text,
            level = parent.level + 1,
            priority = priority.name,
            createdAt = System.currentTimeMillis()
        )
        parent.subtasks.add(subtask)
        notifyListeners()
        return subtask
    }

    override fun removeTaskWithoutUndo(taskId: String): Boolean {
        val result = repository.removeTask(taskId)
        if (result) notifyListeners()
        return result
    }

    override fun updateTaskTextWithoutUndo(taskId: String, newText: String): Boolean {
        val task = tasks.findTaskById(taskId) ?: return false
        task.text = newText
        notifyListeners()
        return true
    }

    override fun setTaskCompletionWithoutUndo(taskId: String, completed: Boolean): Boolean {
        val task = tasks.findTaskById(taskId) ?: return false
        task.isCompleted = completed
        task.completedAt = if (completed) System.currentTimeMillis() else null
        notifyListeners()
        return true
    }

    override fun setTaskPriorityWithoutUndo(taskId: String, priority: Priority): Boolean {
        val task = tasks.findTaskById(taskId) ?: return false
        task.setPriorityEnum(priority)
        notifyListeners()
        return true
    }

    override fun setTaskLocationWithoutUndo(taskId: String, location: CodeLocation?): Boolean {
        val task = tasks.findTaskById(taskId) ?: return false
        task.codeLocation = location?.copy()
        notifyListeners()
        return true
    }

    override fun moveTaskWithoutUndo(
        taskId: String,
        targetParentId: String?,
        targetIndex: Int
    ): Boolean {
        val result = repository.moveTasks(listOf(taskId), targetParentId, targetIndex)
        if (result) notifyListeners()
        return result
    }

    override fun moveTasksWithoutUndo(
        taskIds: List<String>,
        targetParentId: String?,
        targetIndex: Int
    ): Boolean {
        val result = repository.moveTasks(taskIds, targetParentId, targetIndex)
        if (result) notifyListeners()
        return result
    }

    override fun clearCompletedTasksWithoutUndo(): Int {
        val count = repository.clearCompleted()
        if (count > 0) notifyListeners()
        return count
    }

    override fun restoreTask(taskSnapshot: Task, parentId: String?, index: Int) {
        val copy = TaskSnapshot.deepCopy(taskSnapshot)

        if (parentId == null) {
            val insertIndex = index.coerceIn(0, tasks.size)
            tasks.add(insertIndex, copy)
        } else {
            val parent = tasks.findTaskById(parentId)
            if (parent != null) {
                val insertIndex = index.coerceIn(0, parent.subtasks.size)
                parent.subtasks.add(insertIndex, copy)
            } else {
                // Parent no longer exists, add to root
                tasks.add(copy)
            }
        }
        notifyListeners()
    }

    override fun restoreCompletionStates(states: Map<String, Boolean>) {
        states.forEach { (taskId, completed) ->
            tasks.findTaskById(taskId)?.let { task ->
                task.isCompleted = completed
                task.completedAt = if (completed) System.currentTimeMillis() else null
            }
        }
        notifyListeners()
    }
}
