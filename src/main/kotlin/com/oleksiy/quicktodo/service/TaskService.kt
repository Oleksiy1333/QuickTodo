package com.oleksiy.quicktodo.service

import com.oleksiy.quicktodo.model.CodeLocation
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.undo.*
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import java.util.concurrent.CopyOnWriteArrayList

@State(
    name = "com.oleksiy.quicktodo.TaskService",
    storages = [Storage("quicktodo.tasklist.xml")]
)
@Service(Service.Level.PROJECT)
class TaskService : PersistentStateComponent<TaskService.State>, CommandExecutor {

    class State {
        @XCollection(propertyElementName = "tasks", elementName = "task")
        var tasks: MutableList<Task> = mutableListOf()

        @XCollection(propertyElementName = "expandedTaskIds", elementName = "id")
        var expandedTaskIds: MutableSet<String> = mutableSetOf()

        var hideCompleted: Boolean = false
    }

    private var myState = State()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val undoRedoManager = UndoRedoManager(maxHistorySize = 25)

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
        undoRedoManager.clearHistory()
        notifyListeners()
    }

    fun getTasks(): List<Task> = myState.tasks.toList()

    fun getExpandedTaskIds(): Set<String> = myState.expandedTaskIds.toSet()

    fun setExpandedTaskIds(ids: Set<String>) {
        myState.expandedTaskIds.clear()
        myState.expandedTaskIds.addAll(ids)
    }

    fun isHideCompleted(): Boolean = myState.hideCompleted

    fun setHideCompleted(hide: Boolean) {
        if (myState.hideCompleted != hide) {
            myState.hideCompleted = hide
            notifyListeners()
        }
    }

    // ============ Public API (creates undo commands) ============

    fun addTask(text: String, priority: Priority = Priority.NONE): Task {
        val task = Task(text = text, level = 0, priority = priority.name)
        myState.tasks.add(task)
        undoRedoManager.recordCommand(AddTaskCommand(task.id, text, priority))
        notifyListeners()
        return task
    }

    fun addSubtask(parentId: String, text: String, priority: Priority = Priority.NONE): Task? {
        val parent = findTask(parentId) ?: return null
        if (!parent.canAddSubtask()) return null
        val subtask = parent.addSubtask(text, priority)
        undoRedoManager.recordCommand(AddSubtaskCommand(subtask.id, parentId, text, priority))
        notifyListeners()
        return subtask
    }

    fun removeTask(taskId: String): Boolean {
        val task = findTask(taskId) ?: return false
        val parentId = findParentId(taskId)
        val index = getTaskIndex(taskId, parentId)
        val snapshot = TaskSnapshot.deepCopy(task)

        val removed = removeTaskInternal(taskId)
        if (removed) {
            undoRedoManager.recordCommand(RemoveTaskCommand(snapshot, parentId, index))
            notifyListeners()
        }
        return removed
    }

    fun setTaskCompletion(taskId: String, completed: Boolean): Boolean {
        val task = findTask(taskId) ?: return false

        // Capture states before change
        val beforeStates = TaskSnapshot.captureCompletionStates(task)

        // Check if any change will happen
        val willChange = hasCompletionChange(task, completed)
        if (!willChange) return true

        // Apply changes
        task.isCompleted = completed
        setAllSubtasksCompletion(task, completed)

        undoRedoManager.recordCommand(
            SetTaskCompletionCommand(taskId, beforeStates, completed)
        )

        notifyListeners()
        return true
    }

    private fun hasCompletionChange(task: Task, newCompleted: Boolean): Boolean {
        if (task.isCompleted != newCompleted) return true
        return task.subtasks.any { hasCompletionChange(it, newCompleted) }
    }

    private fun setAllSubtasksCompletion(task: Task, completed: Boolean): Boolean {
        var changed = false
        for (subtask in task.subtasks) {
            if (subtask.isCompleted != completed) {
                subtask.isCompleted = completed
                changed = true
            }
            changed = setAllSubtasksCompletion(subtask, completed) || changed
        }
        return changed
    }

    fun setTaskPriority(taskId: String, priority: Priority): Boolean {
        val task = findTask(taskId) ?: return false
        val oldPriority = task.getPriorityEnum()

        if (oldPriority == priority) return true

        task.setPriorityEnum(priority)
        undoRedoManager.recordCommand(SetTaskPriorityCommand(taskId, oldPriority, priority))
        notifyListeners()
        return true
    }

    fun setTaskLocation(taskId: String, location: CodeLocation?): Boolean {
        val task = findTask(taskId) ?: return false
        val oldLocation = task.codeLocation?.copy()

        // Skip if no change
        if (oldLocation == location) return true
        if (oldLocation?.relativePath == location?.relativePath &&
            oldLocation?.line == location?.line &&
            oldLocation?.column == location?.column) return true

        task.codeLocation = location?.copy()
        undoRedoManager.recordCommand(SetTaskLocationCommand(taskId, oldLocation, location?.copy()))
        notifyListeners()
        return true
    }

    fun updateTaskText(taskId: String, newText: String): Boolean {
        val task = findTask(taskId) ?: return false
        val oldText = task.text

        if (oldText == newText) return true

        task.text = newText
        undoRedoManager.recordCommand(EditTaskTextCommand(taskId, oldText, newText))
        notifyListeners()
        return true
    }

    fun moveTask(taskId: String, targetParentId: String?, targetIndex: Int): Boolean {
        return moveTasks(listOf(taskId), targetParentId, targetIndex)
    }

    fun moveTasks(taskIds: List<String>, targetParentId: String?, targetIndex: Int): Boolean {
        if (taskIds.isEmpty()) return false

        // Capture original positions before any changes
        val moveInfos = taskIds.mapNotNull { taskId ->
            val parentId = findParentId(taskId)
            val index = getTaskIndex(taskId, parentId)
            if (index >= 0) {
                MoveTasksCommand.TaskMoveInfo(taskId, parentId, index)
            } else null
        }

        if (moveInfos.size != taskIds.size) return false

        // Execute the move
        val result = moveTasksInternal(taskIds, targetParentId, targetIndex)

        if (result) {
            undoRedoManager.recordCommand(MoveTasksCommand(moveInfos, targetParentId, targetIndex))
            notifyListeners()
        }

        return result
    }

    fun clearCompletedTasks(): Int {
        // Capture all completed tasks before removal
        val removedTasks = captureCompletedTasks(myState.tasks, null)

        if (removedTasks.isEmpty()) return 0

        val count = clearCompletedFromList(myState.tasks)

        undoRedoManager.recordCommand(ClearCompletedTasksCommand(removedTasks))
        notifyListeners()
        return count
    }

    private fun captureCompletedTasks(
        tasks: List<Task>,
        parentId: String?
    ): List<ClearCompletedTasksCommand.RemovedTaskInfo> {
        val result = mutableListOf<ClearCompletedTasksCommand.RemovedTaskInfo>()

        tasks.forEachIndexed { index, task ->
            if (task.isCompleted) {
                result.add(
                    ClearCompletedTasksCommand.RemovedTaskInfo(
                        TaskSnapshot.deepCopy(task),
                        parentId,
                        index
                    )
                )
            } else {
                result.addAll(captureCompletedTasks(task.subtasks, task.id))
            }
        }

        return result
    }

    private fun clearCompletedFromList(tasks: MutableList<Task>): Int {
        var count = 0
        val iterator = tasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.isCompleted) {
                count += 1 + countAllSubtasks(task)
                iterator.remove()
            } else {
                count += clearCompletedFromList(task.subtasks)
            }
        }
        return count
    }

    private fun countAllSubtasks(task: Task): Int {
        var count = task.subtasks.size
        task.subtasks.forEach { count += countAllSubtasks(it) }
        return count
    }

    // ============ Undo/Redo API ============

    fun undo(): Boolean {
        return undoRedoManager.undo(this)
    }

    fun redo(): Boolean {
        return undoRedoManager.redo(this)
    }

    fun canUndo(): Boolean = undoRedoManager.canUndo()
    fun canRedo(): Boolean = undoRedoManager.canRedo()

    fun getUndoDescription(): String? = undoRedoManager.getUndoDescription()
    fun getRedoDescription(): String? = undoRedoManager.getRedoDescription()

    fun addUndoRedoListener(listener: UndoRedoManager.UndoRedoListener) {
        undoRedoManager.addListener(listener)
    }

    fun removeUndoRedoListener(listener: UndoRedoManager.UndoRedoListener) {
        undoRedoManager.removeListener(listener)
    }

    // ============ CommandExecutor Implementation ============

    override fun addTaskWithoutUndo(taskId: String, text: String, priority: Priority): Task {
        val task = Task(id = taskId, text = text, level = 0, priority = priority.name)
        myState.tasks.add(task)
        notifyListeners()
        return task
    }

    override fun addSubtaskWithoutUndo(
        subtaskId: String,
        parentId: String,
        text: String,
        priority: Priority
    ): Task? {
        val parent = findTask(parentId) ?: return null
        if (!parent.canAddSubtask()) return null

        val subtask = Task(
            id = subtaskId,
            text = text,
            level = parent.level + 1,
            priority = priority.name
        )
        parent.subtasks.add(subtask)
        notifyListeners()
        return subtask
    }

    override fun removeTaskWithoutUndo(taskId: String): Boolean {
        val result = removeTaskInternal(taskId)
        if (result) notifyListeners()
        return result
    }

    override fun updateTaskTextWithoutUndo(taskId: String, newText: String): Boolean {
        val task = findTask(taskId) ?: return false
        task.text = newText
        notifyListeners()
        return true
    }

    override fun setTaskCompletionWithoutUndo(taskId: String, completed: Boolean): Boolean {
        val task = findTask(taskId) ?: return false
        task.isCompleted = completed
        setAllSubtasksCompletion(task, completed)
        notifyListeners()
        return true
    }

    override fun setTaskPriorityWithoutUndo(taskId: String, priority: Priority): Boolean {
        val task = findTask(taskId) ?: return false
        task.setPriorityEnum(priority)
        notifyListeners()
        return true
    }

    override fun setTaskLocationWithoutUndo(taskId: String, location: CodeLocation?): Boolean {
        val task = findTask(taskId) ?: return false
        task.codeLocation = location?.copy()
        notifyListeners()
        return true
    }

    override fun moveTaskWithoutUndo(
        taskId: String,
        targetParentId: String?,
        targetIndex: Int
    ): Boolean {
        val result = moveTasksInternal(listOf(taskId), targetParentId, targetIndex)
        if (result) notifyListeners()
        return result
    }

    override fun moveTasksWithoutUndo(
        taskIds: List<String>,
        targetParentId: String?,
        targetIndex: Int
    ): Boolean {
        val result = moveTasksInternal(taskIds, targetParentId, targetIndex)
        if (result) notifyListeners()
        return result
    }

    override fun clearCompletedTasksWithoutUndo(): Int {
        val count = clearCompletedFromList(myState.tasks)
        if (count > 0) notifyListeners()
        return count
    }

    override fun restoreTask(taskSnapshot: Task, parentId: String?, index: Int) {
        val copy = TaskSnapshot.deepCopy(taskSnapshot)

        if (parentId == null) {
            val insertIndex = index.coerceIn(0, myState.tasks.size)
            myState.tasks.add(insertIndex, copy)
        } else {
            val parent = findTask(parentId)
            if (parent != null) {
                val insertIndex = index.coerceIn(0, parent.subtasks.size)
                parent.subtasks.add(insertIndex, copy)
            } else {
                // Parent no longer exists, add to root
                myState.tasks.add(copy)
            }
        }
        notifyListeners()
    }

    override fun restoreCompletionStates(states: Map<String, Boolean>) {
        states.forEach { (taskId, completed) ->
            findTask(taskId)?.isCompleted = completed
        }
        notifyListeners()
    }

    // ============ Helper Methods ============

    private fun getTaskIndex(taskId: String, parentId: String?): Int {
        val siblings = if (parentId == null) {
            myState.tasks
        } else {
            findTask(parentId)?.subtasks ?: return -1
        }
        return siblings.indexOfFirst { it.id == taskId }
    }

    private fun removeTaskInternal(taskId: String): Boolean {
        return removeTaskFromList(myState.tasks, taskId)
    }

    private fun removeTaskFromList(tasks: MutableList<Task>, taskId: String): Boolean {
        val iterator = tasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.id == taskId) {
                iterator.remove()
                return true
            }
            if (removeTaskFromList(task.subtasks, taskId)) {
                return true
            }
        }
        return false
    }

    private fun moveTasksInternal(
        taskIds: List<String>,
        targetParentId: String?,
        targetIndex: Int
    ): Boolean {
        if (taskIds.isEmpty()) return false

        // Collect all tasks to move (in order)
        val tasksToMove = taskIds.mapNotNull { findTask(it) }
        if (tasksToMove.size != taskIds.size) return false

        // Calculate index adjustment for tasks being removed from same parent before target
        val indexAdjustment = calculateIndexAdjustment(taskIds, targetParentId, targetIndex)

        // Remove all tasks from their current locations
        for (taskId in taskIds) {
            if (!removeTaskInternal(taskId)) return false
        }

        // Adjust target index to account for removed items
        val adjustedTargetIndex = targetIndex - indexAdjustment

        // Insert at new location
        if (targetParentId == null) {
            // Moving to root level
            var insertIndex = adjustedTargetIndex.coerceIn(0, myState.tasks.size)
            for (task in tasksToMove) {
                task.level = 0
                updateSubtaskLevels(task, 0)
                myState.tasks.add(insertIndex, task)
                insertIndex++
            }
        } else {
            // Moving under a parent
            val targetParent = findTask(targetParentId) ?: return false
            if (!targetParent.canAddSubtask()) return false
            var insertIndex = adjustedTargetIndex.coerceIn(0, targetParent.subtasks.size)
            for (task in tasksToMove) {
                task.level = targetParent.level + 1
                updateSubtaskLevels(task, task.level)
                targetParent.subtasks.add(insertIndex, task)
                insertIndex++
            }
        }

        return true
    }

    private fun calculateIndexAdjustment(
        taskIds: List<String>,
        targetParentId: String?,
        targetIndex: Int
    ): Int {
        val targetSiblings = if (targetParentId == null) {
            myState.tasks
        } else {
            findTask(targetParentId)?.subtasks ?: return 0
        }

        // Count how many source tasks are in the same parent AND before the target index
        return taskIds.count { taskId ->
            val index = targetSiblings.indexOfFirst { it.id == taskId }
            index >= 0 && index < targetIndex
        }
    }

    private fun updateSubtaskLevels(task: Task, parentLevel: Int) {
        task.level = parentLevel
        task.subtasks.forEach { subtask ->
            updateSubtaskLevels(subtask, parentLevel + 1)
        }
    }

    fun findTask(taskId: String): Task? {
        for (task in myState.tasks) {
            task.findTask(taskId)?.let { return it }
        }
        return null
    }

    fun findParentId(taskId: String): String? {
        return findParentIdRecursive(myState.tasks, taskId, null)
    }

    private fun findParentIdRecursive(
        tasks: List<Task>,
        targetId: String,
        currentParentId: String?
    ): String? {
        for (task in tasks) {
            if (task.id == targetId) return currentParentId
            val result = findParentIdRecursive(task.subtasks, targetId, task.id)
            if (result != null) return result
        }
        return null
    }

    fun isAncestorOf(ancestorId: String, descendantId: String): Boolean {
        val ancestor = findTask(ancestorId) ?: return false
        return ancestor.findTask(descendantId) != null
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    companion object {
        fun getInstance(project: Project): TaskService {
            return project.getService(TaskService::class.java)
        }
    }
}
