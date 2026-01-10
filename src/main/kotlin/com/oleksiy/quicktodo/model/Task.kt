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

    @Attribute("description")
    var descriptionLegacy: String = "",

    @Tag("description")
    var description: String = "",

    @Attribute("completed")
    var isCompleted: Boolean = false,

    @Attribute("level")
    var level: Int = 0,

    @Attribute("priority")
    var priority: String = Priority.NONE.name,

    @Attribute("ownTimeSpentMs")
    var ownTimeSpentMs: Long = 0,

    // TODO: Legacy field for migration
    @Attribute("totalTimeSpentMs")
    var totalTimeSpentMs: Long = 0,

    @Attribute("lastFocusStartedAt")
    var lastFocusStartedAt: Long? = null,

    // Derived field
    @Transient
    var accumulatedHierarchyTimeMs: Long = 0,

    // Derived field
    @Transient
    var lastAccumulatedFocusStartedAt: Long? = null,

    @Attribute("createdAt")
    var createdAt: Long? = null,

    @Attribute("completedAt")
    var completedAt: Long? = null,

    @Attribute("lastModified")
    var lastModified: Long = System.currentTimeMillis(),

    var codeLocation: CodeLocation? = null,

    @XCollection(propertyElementName = "subtasks", elementName = "task")
    var subtasks: MutableList<Task> = mutableListOf()
) {
    constructor() : this(
        UUID.randomUUID().toString(), // id
        "",                           // text
        "",                           // descriptionLegacy
        "",                           // description
        false,                        // isCompleted
        0,                            // level
        Priority.NONE.name,           // priority
        0,                            // ownTimeSpentMs
        0,                            // totalTimeSpentMs
        null,                         // lastFocusStartedAt
        0,                            // accumulatedHierarchyTimeMs
        null,                         // lastAccumulatedFocusStartedAt
        null,                         // createdAt
        null,                         // completedAt
        System.currentTimeMillis(),   // lastModified
        null,                         // codeLocation
        mutableListOf()               // subtasks
    )

    init {
        // Migration: copy totalTimeSpentMs to ownTimeSpentMs if ownTimeSpentMs is 0
        if (ownTimeSpentMs == 0L && totalTimeSpentMs > 0L) {
            ownTimeSpentMs = totalTimeSpentMs
        }
    }

    fun getPriorityEnum(): Priority = Priority.fromString(priority)

    fun setPriorityEnum(p: Priority) {
        priority = p.name
    }

    fun canAddSubtask(): Boolean = level < ChecklistConstants.MAX_NESTING_LEVEL

    fun hasCodeLocation(): Boolean = codeLocation?.isValid() == true

    fun hasDescription(): Boolean = description.isNotBlank()

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
