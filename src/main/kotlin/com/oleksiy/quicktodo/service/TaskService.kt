package com.oleksiy.quicktodo.service

import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

@State(
    name = "com.oleksiy.quicktodo.TaskService",
    storages = [Storage("quicktodo.tasklist.xml")]
)
@Service(Service.Level.PROJECT)
class TaskService : PersistentStateComponent<TaskService.State> {

    data class RemovedTaskInfo(
        val task: Task,
        val parentId: String?,
        val index: Int
    )

    class State {
        @XCollection(propertyElementName = "tasks", elementName = "task")
        var tasks: MutableList<Task> = mutableListOf()

        @XCollection(propertyElementName = "expandedTaskIds", elementName = "id")
        var expandedTaskIds: MutableSet<String> = mutableSetOf()
    }

    private var myState = State()
    private val listeners = mutableListOf<() -> Unit>()
    private val undoStack = ArrayDeque<RemovedTaskInfo>()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
        notifyListeners()
    }

    fun getTasks(): List<Task> = myState.tasks.toList()

    fun getExpandedTaskIds(): Set<String> = myState.expandedTaskIds.toSet()

    fun setExpandedTaskIds(ids: Set<String>) {
        myState.expandedTaskIds.clear()
        myState.expandedTaskIds.addAll(ids)
    }

    fun addTask(text: String, priority: Priority = Priority.NONE): Task {
        val task = Task(text = text, level = 0, priority = priority.name)
        myState.tasks.add(task)
        notifyListeners()
        return task
    }

    fun addSubtask(parentId: String, text: String, priority: Priority = Priority.NONE): Task? {
        val parent = findTask(parentId) ?: return null
        if (!parent.canAddSubtask()) return null
        val subtask = parent.addSubtask(text, priority)
        notifyListeners()
        return subtask
    }

    fun removeTask(taskId: String): Boolean {
        val result = findAndRemoveTask(myState.tasks, taskId, null, captureInfo = true)
        if (result != null) {
            undoStack.addLast(result)
            notifyListeners()
            return true
        }
        return false
    }

    fun undoRemoveTask(): Boolean {
        if (undoStack.isEmpty()) return false

        val info = undoStack.removeLast()

        if (info.parentId == null) {
            // Restore to root level
            val index = info.index.coerceIn(0, myState.tasks.size)
            myState.tasks.add(index, info.task)
        } else {
            // Restore to parent
            val parent = findTask(info.parentId)
            if (parent != null) {
                val index = info.index.coerceIn(0, parent.subtasks.size)
                parent.subtasks.add(index, info.task)
            } else {
                // Parent no longer exists, add to root
                myState.tasks.add(info.task)
            }
        }

        notifyListeners()
        return true
    }

    fun setTaskCompletion(taskId: String, completed: Boolean): Boolean {
        val task = findTask(taskId) ?: return false
        if (task.isCompleted != completed) {
            task.isCompleted = completed
            notifyListeners()
        }
        return true
    }

    fun setTaskPriority(taskId: String, priority: Priority): Boolean {
        val task = findTask(taskId) ?: return false
        if (task.getPriorityEnum() != priority) {
            task.setPriorityEnum(priority)
            notifyListeners()
        }
        return true
    }

    fun clearCompletedTasks(): Int {
        var count = 0
        count += clearCompletedFromList(myState.tasks)
        if (count > 0) {
            notifyListeners()
        }
        return count
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

    fun updateTaskText(taskId: String, newText: String): Boolean {
        val task = findTask(taskId) ?: return false
        task.text = newText
        notifyListeners()
        return true
    }

    fun moveTask(taskId: String, targetParentId: String?, targetIndex: Int): Boolean {
        val task = findTask(taskId) ?: return false

        // Remove task from its current location (without capturing undo info)
        findAndRemoveTask(myState.tasks, taskId, null, captureInfo = false) ?: return false

        // Insert at new location
        if (targetParentId == null) {
            // Moving to root level
            val insertIndex = targetIndex.coerceIn(0, myState.tasks.size)
            task.level = 0
            updateSubtaskLevels(task, 0)
            myState.tasks.add(insertIndex, task)
        } else {
            // Moving under a parent
            val targetParent = findTask(targetParentId) ?: return false
            if (!targetParent.canAddSubtask()) return false
            val insertIndex = targetIndex.coerceIn(0, targetParent.subtasks.size)
            task.level = targetParent.level + 1
            updateSubtaskLevels(task, task.level)
            targetParent.subtasks.add(insertIndex, task)
        }

        notifyListeners()
        return true
    }

    private fun updateSubtaskLevels(task: Task, parentLevel: Int) {
        task.level = parentLevel
        task.subtasks.forEach { subtask ->
            updateSubtaskLevels(subtask, parentLevel + 1)
        }
    }

    /**
     * Generic tree traversal that finds and removes a task.
     * @param tasks The list to search in
     * @param taskId The ID of the task to find and remove
     * @param parentId The ID of the parent (null for root level)
     * @param captureInfo Whether to capture removal info for undo
     * @return RemovedTaskInfo if found and removed (when captureInfo=true),
     *         or a dummy RemovedTaskInfo (when captureInfo=false and found),
     *         or null if not found
     */
    private fun findAndRemoveTask(
        tasks: MutableList<Task>,
        taskId: String,
        parentId: String?,
        captureInfo: Boolean
    ): RemovedTaskInfo? {
        for (i in tasks.indices) {
            val task = tasks[i]
            if (task.id == taskId) {
                tasks.removeAt(i)
                return if (captureInfo) {
                    RemovedTaskInfo(task, parentId, i)
                } else {
                    // Return a dummy info to indicate success
                    RemovedTaskInfo(task, null, 0)
                }
            }
            val result = findAndRemoveTask(task.subtasks, taskId, task.id, captureInfo)
            if (result != null) return result
        }
        return null
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
