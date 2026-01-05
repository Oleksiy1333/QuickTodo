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

    fun updateTaskDescription(taskId: String, newDescription: String): Boolean {
        val task = findTask(taskId) ?: return false
        val oldDescription = task.description

        if (oldDescription == newDescription) return true

        task.description = newDescription
        undoRedoManager.recordCommand(EditTaskDescriptionCommand(taskId, oldDescription, newDescription))
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
            undoRedoManager.recordCommand(MoveTasksCommand(moveInfos, targetParentId, targetIndex))
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
