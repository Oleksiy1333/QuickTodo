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
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

@State(
    name = "com.oleksiy.quicktodo.TaskService",
    storages = [Storage("quicktodo.tasklist.xml")]
)
@Service(Service.Level.PROJECT)
class TaskService : PersistentStateComponent<TaskService.State> {

    class State {
        @XCollection(propertyElementName = "tasks", elementName = "task")
        var tasks: MutableList<Task> = mutableListOf()

        @XCollection(propertyElementName = "expandedTaskIds", elementName = "id")
        var expandedTaskIds: MutableSet<String> = mutableSetOf()

        var hideCompleted: Boolean = false

        var focusTimeByDate: MutableMap<String, Long> = mutableMapOf()
    }

    private var myState = State()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val undoRedoManager = UndoRedoManager(maxHistorySize = 25)

    // Delegate components
    private val repository = TaskRepository { myState.tasks }
    private val undoSupport = TaskUndoSupport(
        tasksProvider = { myState.tasks },
        repository = repository,
        notifyListeners = { notifyListeners() }
    )

    // ============ Persistence ============

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
        migrateTaskTimestamps(myState.tasks)
        migrateTimeTracking(myState.tasks)
        // Recalculate hierarchy time after loading state
        recalculateHierarchyTime()
        undoRedoManager.clearHistory()
        notifyListeners()
    }

    private fun migrateTaskTimestamps(tasks: List<Task>) {
        val now = System.currentTimeMillis()
        for (task in tasks) {
            if (task.createdAt == null) {
                task.createdAt = now
            }
            if (task.isCompleted && task.completedAt == null) {
                task.completedAt = now
            }
            migrateTaskTimestamps(task.subtasks)
        }
    }

    /**
     * Migrates old totalTimeSpentMs to the new ownTimeSpentMs field.
     * This properly handles the transition from the old time tracking system
     * where totalTimeSpentMs included both own and child time.
     * After migration, totalTimeSpentMs is cleared so it won't be persisted.
     */
    private fun migrateTimeTracking(tasks: List<Task>) {
        for (task in tasks) {
            // Migration strategy: if ownTimeSpentMs is 0 but totalTimeSpentMs has a value,
            // we need to split it intelligently
            if (task.ownTimeSpentMs == 0L && task.totalTimeSpentMs > 0L) {
                // First, recursively migrate children
                migrateTimeTracking(task.subtasks)

                // Calculate children's total time
                val childrenTotalTime = task.subtasks.sumOf { it.totalTimeSpentMs }

                if (childrenTotalTime > 0L) {
                    // Parent had accumulated time - split it
                    // Own time = total - children's time (but ensure non-negative)
                    task.ownTimeSpentMs = (task.totalTimeSpentMs - childrenTotalTime).coerceAtLeast(0L)
                } else {
                    // No children or children have no time - all time is own time
                    task.ownTimeSpentMs = task.totalTimeSpentMs
                }

                // Clear the legacy field after migration so it won't be persisted
                task.totalTimeSpentMs = 0
            } else {
                // Already migrated or no time to migrate, just process children
                migrateTimeTracking(task.subtasks)
            }
        }
    }

    // ============ State Queries ============

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

    // ============ Tree Traversal (delegates to TaskTreeUtils) ============

    fun findTask(taskId: String): Task? = myState.tasks.findTaskById(taskId)

    fun findParentId(taskId: String): String? = findParentId(myState.tasks, taskId)

    fun isAncestorOf(ancestorId: String, descendantId: String): Boolean =
        isAncestorOf(myState.tasks, ancestorId, descendantId)

    // ============ Public CRUD API (creates undo commands) ============

    fun addTask(text: String, priority: Priority = Priority.NONE): Task {
        val task = Task(text = text, level = 0, priority = priority.name, createdAt = System.currentTimeMillis())
        myState.tasks.add(task)
        undoRedoManager.recordCommand(AddTaskCommand(task.id, text, priority))
        notifyListeners()
        return task
    }

    fun addSubtask(parentId: String, text: String, priority: Priority = Priority.NONE): Task? {
        val parent = findTask(parentId) ?: return null
        if (!parent.canAddSubtask()) return null
        val subtask = parent.addSubtask(text, priority)
        subtask.createdAt = System.currentTimeMillis()
        undoRedoManager.recordCommand(AddSubtaskCommand(subtask.id, parentId, text, priority))
        notifyListeners()
        return subtask
    }

    fun removeTask(taskId: String): Boolean {
        val task = findTask(taskId) ?: return false
        val parentId = findParentId(taskId)
        val index = getTaskIndex(myState.tasks, taskId, parentId)
        val snapshot = TaskSnapshot.deepCopy(task)

        val removed = repository.removeTask(taskId)
        if (removed) {
            undoRedoManager.recordCommand(RemoveTaskCommand(snapshot, parentId, index))
            notifyListeners()
        }
        return removed
    }

    fun removeTasks(taskIds: List<String>): Boolean {
        if (taskIds.isEmpty()) return false

        // Filter out descendants of tasks being deleted (avoid double removal)
        val filtered = taskIds.filter { id ->
            taskIds.none { otherId ->
                otherId != id && isAncestorOf(otherId, id)
            }
        }

        // Capture snapshots before deletion
        val removedInfos = filtered.mapNotNull { taskId ->
            val task = findTask(taskId) ?: return@mapNotNull null
            val parentId = findParentId(taskId)
            val index = getTaskIndex(myState.tasks, taskId, parentId)
            RemoveMultipleTasksCommand.RemovedTaskInfo(
                TaskSnapshot.deepCopy(task), parentId, index
            )
        }

        if (removedInfos.isEmpty()) return false

        // Remove all tasks
        removedInfos.forEach { repository.removeTask(it.taskSnapshot.id) }

        undoRedoManager.recordCommand(RemoveMultipleTasksCommand(removedInfos))
        notifyListeners()
        return true
    }

    fun setTaskCompletion(taskId: String, completed: Boolean): Boolean {
        val task = findTask(taskId) ?: return false

        if (task.isCompleted == completed) return true

        val beforeStates = mapOf(task.id to task.isCompleted)
        task.isCompleted = completed
        task.completedAt = if (completed) System.currentTimeMillis() else null
        task.lastModified = System.currentTimeMillis()

        undoRedoManager.recordCommand(
            SetTaskCompletionCommand(taskId, beforeStates, completed)
        )
        notifyListeners()
        return true
    }

    fun setTaskPriority(taskId: String, priority: Priority): Boolean {
        val task = findTask(taskId) ?: return false
        val oldPriority = task.getPriorityEnum()

        if (oldPriority == priority) return true

        task.setPriorityEnum(priority)
        task.lastModified = System.currentTimeMillis()
        undoRedoManager.recordCommand(SetTaskPriorityCommand(taskId, oldPriority, priority))
        notifyListeners()
        return true
    }

    fun setTaskLocation(taskId: String, location: CodeLocation?): Boolean {
        val task = findTask(taskId) ?: return false
        val oldLocation = task.codeLocation?.copy()

        if (oldLocation == location) return true
        if (oldLocation?.relativePath == location?.relativePath &&
            oldLocation?.line == location?.line &&
            oldLocation?.column == location?.column) return true

        task.codeLocation = location?.copy()
        task.lastModified = System.currentTimeMillis()
        undoRedoManager.recordCommand(SetTaskLocationCommand(taskId, oldLocation, location?.copy()))
        notifyListeners()
        return true
    }

    fun updateTaskText(taskId: String, newText: String): Boolean {
        val task = findTask(taskId) ?: return false
        val oldText = task.text

        if (oldText == newText) return true

        task.text = newText
        task.lastModified = System.currentTimeMillis()
        undoRedoManager.recordCommand(EditTaskTextCommand(taskId, oldText, newText))
        notifyListeners()
        return true
    }

    fun updateTaskOwnTime(taskId: String, newTimeMs: Long): Boolean {
        val task = findTask(taskId) ?: return false

        if (task.ownTimeSpentMs == newTimeMs) return true

        task.ownTimeSpentMs = newTimeMs
        task.lastModified = System.currentTimeMillis()

        // Recalculate hierarchy time after changing own time
        recalculateHierarchyTime()

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
            val index = getTaskIndex(myState.tasks, taskId, parentId)
            if (index >= 0) {
                MoveTasksCommand.TaskMoveInfo(taskId, parentId, index)
            } else null
        }

        if (moveInfos.size != taskIds.size) return false

        val result = repository.moveTasks(taskIds, targetParentId, targetIndex)

        if (result) {
            // Update lastModified for all moved tasks
            taskIds.forEach { taskId ->
                findTask(taskId)?.lastModified = System.currentTimeMillis()
            }
            undoRedoManager.recordCommand(MoveTasksCommand(moveInfos, targetParentId, targetIndex))

            // Recalculate hierarchy time after task movement
            recalculateHierarchyTime()

            notifyListeners()
        }

        return result
    }

    fun clearCompletedTasks(): Int {
        val removedTasks = repository.captureCompletedTasks()

        if (removedTasks.isEmpty()) return 0

        val count = repository.clearCompleted()

        undoRedoManager.recordCommand(ClearCompletedTasksCommand(removedTasks))
        notifyListeners()
        return count
    }

    // ============ Undo/Redo API ============

    fun undo(): Boolean = undoRedoManager.undo(undoSupport)

    fun redo(): Boolean = undoRedoManager.redo(undoSupport)

    fun canUndo(): Boolean = undoRedoManager.canUndo()
    fun canRedo(): Boolean = undoRedoManager.canRedo()

    fun getUndoDescription(): String? = undoRedoManager.getUndoDescription()
    fun getRedoDescription(): String? = undoRedoManager.getRedoDescription()

    // ============ Hierarchy Time Calculation ============

    /**
     * Recalculates accumulatedHierarchyTimeMs for all tasks in the tree.
     * This must be called after any timer stop/pause or task movement.
     */
    fun recalculateHierarchyTime() {
        // Reset all accumulated times first
        fun resetAccumulatedTime(tasks: List<Task>) {
            for (task in tasks) {
                task.accumulatedHierarchyTimeMs = 0
                resetAccumulatedTime(task.subtasks)
            }
        }
        resetAccumulatedTime(myState.tasks)

        // Calculate accumulated time bottom-up
        fun calculateAccumulatedTime(task: Task): Long {
            // Start with own time
            var total = task.ownTimeSpentMs

            // Add each child's total time (own + their accumulated)
            for (subtask in task.subtasks) {
                total += calculateAccumulatedTime(subtask)
            }

            // Store the accumulated time (excludes own time)
            task.accumulatedHierarchyTimeMs = total - task.ownTimeSpentMs

            return total
        }

        // Calculate for all root tasks
        for (task in myState.tasks) {
            calculateAccumulatedTime(task)
        }
    }

    // ============ Listener Management ============

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    // ============ Daily Focus Time ============

    fun addFocusTime(durationMs: Long) {
        val today = LocalDate.now().toString()
        myState.focusTimeByDate[today] = (myState.focusTimeByDate[today] ?: 0L) + durationMs
        cleanupOldFocusTimeEntries()
    }

    fun getTodayFocusTime(): Long {
        return myState.focusTimeByDate[LocalDate.now().toString()] ?: 0L
    }

    private fun cleanupOldFocusTimeEntries() {
        val cutoffDate = LocalDate.now().minusDays(7).toString()
        myState.focusTimeByDate.keys.removeIf { it < cutoffDate }
    }

    companion object {
        fun getInstance(project: Project): TaskService {
            return project.getService(TaskService::class.java)
        }
    }
}
