package com.oleksiy.quicktodo.undo

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages undo/redo stacks for the QuickTodo plugin.
 * Owned by TaskService to maintain tight coupling with task state.
 */
class UndoRedoManager(
    private val maxHistorySize: Int = 25
) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()
    private val listeners = CopyOnWriteArrayList<UndoRedoListener>()

    interface UndoRedoListener {
        fun onUndoRedoStateChanged()
    }

    /**
     * Records a new command and clears the redo stack.
     * This is the standard undo/redo behavior - any new action invalidates redo history.
     */
    fun recordCommand(command: Command) {
        undoStack.addLast(command)

        // Enforce history limit
        while (undoStack.size > maxHistorySize) {
            undoStack.removeFirst()
        }

        // Clear redo stack when new action is performed
        redoStack.clear()

        notifyListeners()
    }

    /**
     * Executes undo if available.
     * @return true if undo was performed
     */
    fun undo(executor: CommandExecutor): Boolean {
        val command = undoStack.removeLastOrNull() ?: return false
        command.undo(executor)
        redoStack.addLast(command)
        notifyListeners()
        return true
    }

    /**
     * Executes redo if available.
     * @return true if redo was performed
     */
    fun redo(executor: CommandExecutor): Boolean {
        val command = redoStack.removeLastOrNull() ?: return false
        command.redo(executor)
        undoStack.addLast(command)
        notifyListeners()
        return true
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun getUndoDescription(): String? = undoStack.lastOrNull()?.description
    fun getRedoDescription(): String? = redoStack.lastOrNull()?.description

    /**
     * Clears all history. Called when state is externally modified
     * (e.g., loading from persistence on project open).
     */
    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        notifyListeners()
    }

    fun addListener(listener: UndoRedoListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: UndoRedoListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.onUndoRedoStateChanged() }
    }
}
