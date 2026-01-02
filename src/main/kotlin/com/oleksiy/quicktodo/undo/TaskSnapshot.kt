package com.oleksiy.quicktodo.undo

import com.oleksiy.quicktodo.model.Task

/**
 * Utility functions for creating deep copies of tasks.
 * Required for undo/redo to work correctly with mutable Task objects.
 */
object TaskSnapshot {

    /**
     * Creates a deep copy of a task including all subtasks.
     */
    fun deepCopy(task: Task): Task {
        return Task(
            id = task.id,
            text = task.text,
            isCompleted = task.isCompleted,
            level = task.level,
            priority = task.priority,
            totalTimeSpentMs = task.totalTimeSpentMs,
            lastFocusStartedAt = task.lastFocusStartedAt,
            createdAt = task.createdAt,
            completedAt = task.completedAt,
            codeLocation = task.codeLocation?.copy(),
            subtasks = task.subtasks.map { deepCopy(it) }.toMutableList()
        )
    }

    /**
     * Captures completion states for a task and all its subtasks.
     * @return Map of taskId -> isCompleted
     */
    fun captureCompletionStates(task: Task): Map<String, Boolean> {
        val states = mutableMapOf<String, Boolean>()
        captureCompletionStatesRecursive(task, states)
        return states
    }

    private fun captureCompletionStatesRecursive(task: Task, states: MutableMap<String, Boolean>) {
        states[task.id] = task.isCompleted
        task.subtasks.forEach { captureCompletionStatesRecursive(it, states) }
    }
}
