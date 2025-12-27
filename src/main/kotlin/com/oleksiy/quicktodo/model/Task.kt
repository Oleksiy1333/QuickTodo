package com.oleksiy.quicktodo.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.oleksiy.quicktodo.ui.ChecklistConstants
import java.util.UUID

@Tag("task")
data class Task(
    @Attribute("id")
    var id: String = UUID.randomUUID().toString(),

    @Attribute("text")
    var text: String = "",

    @Attribute("completed")
    var isCompleted: Boolean = false,

    @Attribute("level")
    var level: Int = 0,

    @Attribute("priority")
    var priority: String = Priority.NONE.name,

    @Attribute("totalTimeSpentMs")
    var totalTimeSpentMs: Long = 0,

    @Attribute("lastFocusStartedAt")
    var lastFocusStartedAt: Long? = null,

    @XCollection(propertyElementName = "subtasks", elementName = "task")
    var subtasks: MutableList<Task> = mutableListOf()
) {
    constructor() : this(UUID.randomUUID().toString(), "", false, 0, Priority.NONE.name, 0, null, mutableListOf())

    fun getPriorityEnum(): Priority = Priority.fromString(priority)

    fun setPriorityEnum(p: Priority) {
        priority = p.name
    }

    fun canAddSubtask(): Boolean = level < ChecklistConstants.MAX_NESTING_LEVEL

    fun addSubtask(text: String, priority: Priority = Priority.NONE): Task {
        require(canAddSubtask()) { "Maximum nesting level reached" }
        val subtask = Task(
            text = text,
            level = level + 1,
            priority = priority.name
        )
        subtasks.add(subtask)
        return subtask
    }

    fun removeSubtask(taskId: String): Boolean {
        val iterator = subtasks.iterator()
        while (iterator.hasNext()) {
            val subtask = iterator.next()
            if (subtask.id == taskId) {
                iterator.remove()
                return true
            }
            if (subtask.removeSubtask(taskId)) {
                return true
            }
        }
        return false
    }

    fun findTask(taskId: String): Task? {
        if (id == taskId) return this
        for (subtask in subtasks) {
            subtask.findTask(taskId)?.let { return it }
        }
        return null
    }
}
