package com.oleksiy.quicktodo.ui

import com.intellij.ui.CheckedTreeNode
import com.oleksiy.quicktodo.model.TaskDateGroup

/**
 * Tree node representing a group header (e.g., "Today (3)", "Overdue (1)").
 * Not a task — just a visual grouping label with expand/collapse support.
 */
class GroupHeaderNode(
    val group: TaskDateGroup,
    val taskCount: Int
) : CheckedTreeNode(GroupHeaderData(group, taskCount)) {

    val groupId: String
        get() = when (group) {
            TaskDateGroup.TODAY -> GROUP_ID_TODAY
            TaskDateGroup.OVERDUE -> GROUP_ID_OVERDUE
            TaskDateGroup.NONE -> GROUP_ID_NONE
        }

    companion object {
        const val GROUP_ID_TODAY = "__group_today__"
        const val GROUP_ID_OVERDUE = "__group_overdue__"
        const val GROUP_ID_NONE = "__group_none__"
    }
}

data class GroupHeaderData(
    val group: TaskDateGroup,
    val taskCount: Int
) {
    val displayName: String
        get() = when (group) {
            TaskDateGroup.TODAY -> "Today"
            TaskDateGroup.OVERDUE -> "Overdue"
            TaskDateGroup.NONE -> "Tasks"
        }

    override fun toString(): String = "$displayName ($taskCount)"
}
