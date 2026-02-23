package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.model.TaskDateGroup
import com.oleksiy.quicktodo.model.TaskDateHelper
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.settings.QuickTodoSettings
import com.intellij.ui.CheckedTreeNode
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Manages tree state including node creation, expansion state, and task selection.
 * Supports grouping tasks into Overdue/Today/Regular sections based on plannedDate.
 */
class TaskTreeManager(
    private val tree: JTree,
    private val taskService: TaskService
) {
    private var isRefreshing = false

    fun isRefreshing(): Boolean = isRefreshing

    /**
     * Refreshes the tree with current tasks, preserving expansion and selection state.
     * Groups tasks into Overdue/Today/Regular sections when planned dates exist.
     */
    fun refreshTree() {
        isRefreshing = true
        try {
            val expandedIds = getExpandedIdsFromTree() + taskService.getExpandedTaskIds()
            val selectedTaskId = getSelectedTaskId()

            val tasks = taskService.getTasks()
            val hideCompleted = taskService.isHideCompleted()
            val rolloverHour = QuickTodoSettings.getInstance().getDayRolloverHour()
            val rootNode = CheckedTreeNode("Tasks")

            // Classify root tasks into groups
            val overdueRoots = mutableListOf<Task>()
            val todayRoots = mutableListOf<Task>()
            val regularRoots = mutableListOf<Task>()

            for (task in tasks) {
                val group = classifyRootTask(task, rolloverHour)
                when (group) {
                    TaskDateGroup.OVERDUE -> overdueRoots.add(task)
                    TaskDateGroup.TODAY -> todayRoots.add(task)
                    TaskDateGroup.NONE -> regularRoots.add(task)
                }
            }

            // Collect subtasks that should be extracted to a different group than their root
            val extracted = collectExtractedSubtasks(tasks, rolloverHour)

            val hasGroups = overdueRoots.isNotEmpty() || todayRoots.isNotEmpty() ||
                extracted.overdue.isNotEmpty() || extracted.today.isNotEmpty()

            if (hasGroups) {
                // Merge extracted subtasks into group lists
                val allOverdue = overdueRoots + extracted.overdue
                val allToday = todayRoots + extracted.today

                // Build grouped tree: Overdue -> Today -> Regular
                addGroupIfNotEmpty(rootNode, TaskDateGroup.OVERDUE, allOverdue, hideCompleted, extracted.extractedIds)
                addGroupIfNotEmpty(rootNode, TaskDateGroup.TODAY, allToday, hideCompleted, extracted.extractedIds)

                // Regular tasks also get a group header for clear separation
                addGroupIfNotEmpty(rootNode, TaskDateGroup.NONE, regularRoots, hideCompleted, extracted.extractedIds)
            } else {
                // No planned tasks — flat tree as before
                for (task in tasks) {
                    val node = createTaskNode(task, hideCompleted)
                    if (node != null) {
                        rootNode.add(node)
                    }
                }
            }

            tree.model = DefaultTreeModel(rootNode)
            restoreExpandedState(expandedIds)

            if (selectedTaskId != null) {
                selectTaskById(selectedTaskId)
            }
        } finally {
            isRefreshing = false
        }
    }

    /**
     * Classifies a root-level task into a date group based on its own planned date only.
     * Subtask planned dates do not affect the parent's group.
     */
    private fun classifyRootTask(task: Task, rolloverHour: Int): TaskDateGroup {
        return TaskDateHelper.classifyTask(task, rolloverHour)
    }

    private data class ExtractedSubtasks(
        val overdue: List<Task>,
        val today: List<Task>,
        val extractedIds: Set<String>
    )

    /**
     * Collects subtasks whose date group differs from their root ancestor's group.
     * These subtasks will be extracted and shown as standalone items in the appropriate group.
     * Subtasks in the same group as their root stay nested (no extraction needed).
     */
    private fun collectExtractedSubtasks(tasks: List<Task>, rolloverHour: Int): ExtractedSubtasks {
        val overdue = mutableListOf<Task>()
        val today = mutableListOf<Task>()
        val ids = mutableSetOf<String>()

        fun scan(subtasks: List<Task>, rootGroup: TaskDateGroup) {
            for (s in subtasks) {
                val group = TaskDateHelper.classifyTask(s, rolloverHour)
                if (group != TaskDateGroup.NONE && group != rootGroup) {
                    when (group) {
                        TaskDateGroup.OVERDUE -> overdue.add(s)
                        TaskDateGroup.TODAY -> today.add(s)
                        else -> {}
                    }
                    ids.add(s.id)
                }
                scan(s.subtasks, rootGroup)
            }
        }

        tasks.forEach { task ->
            val rootGroup = classifyRootTask(task, rolloverHour)
            scan(task.subtasks, rootGroup)
        }
        return ExtractedSubtasks(overdue, today, ids)
    }

    /**
     * Adds a group header with its tasks to the root node.
     * Counts only visible root-level tasks for the header counter.
     */
    private fun addGroupIfNotEmpty(
        rootNode: CheckedTreeNode,
        group: TaskDateGroup,
        tasks: List<Task>,
        hideCompleted: Boolean,
        extractedIds: Set<String> = emptySet()
    ) {
        val visibleNodes = tasks.mapNotNull { createTaskNode(it, hideCompleted, extractedIds) }
        if (visibleNodes.isEmpty()) return

        val groupHeader = GroupHeaderNode(group, visibleNodes.size)
        for (node in visibleNodes) {
            groupHeader.add(node)
        }
        rootNode.add(groupHeader)
    }

    private fun getSelectedTaskId(): String? {
        val node = tree.lastSelectedPathComponent as? CheckedTreeNode
        return (node?.userObject as? Task)?.id
    }

    private fun hasIncompleteDescendants(task: Task): Boolean =
        task.subtasks.any { !it.isCompleted || hasIncompleteDescendants(it) }

    private fun createTaskNode(
        task: Task,
        hideCompleted: Boolean,
        extractedIds: Set<String> = emptySet()
    ): CheckedTreeNode? {
        if (hideCompleted && task.isCompleted && !hasIncompleteDescendants(task)) {
            return null
        }
        val node = CheckedTreeNode(task)
        node.isChecked = task.isCompleted
        task.subtasks.forEach { subtask ->
            if (subtask.id in extractedIds) return@forEach
            val subtaskNode = createTaskNode(subtask, hideCompleted, extractedIds)
            if (subtaskNode != null) {
                node.add(subtaskNode)
            }
        }
        return node
    }

    fun expandAll() {
        val root = tree.model.root as? CheckedTreeNode ?: return
        traverseNodes(root) { node ->
            tree.expandPath(TreePath(node.path))
        }
    }

    fun collapseAll() {
        val root = tree.model.root as? CheckedTreeNode ?: return
        traverseNodesPostOrder(root) { node ->
            if (node.parent != null) {
                tree.collapsePath(TreePath(node.path))
            }
        }
    }

    fun saveExpandedState() {
        val expandedIds = getExpandedIdsFromTree()
        taskService.setExpandedTaskIds(expandedIds)
    }

    /**
     * Gets all expanded IDs from the current tree state.
     * Includes both task IDs and group header IDs.
     */
    fun getExpandedIdsFromTree(): Set<String> {
        val expandedIds = mutableSetOf<String>()
        val root = tree.model.root as? CheckedTreeNode ?: return expandedIds

        traverseNodes(root) { node ->
            if (tree.isExpanded(TreePath(node.path))) {
                when (node) {
                    is GroupHeaderNode -> expandedIds.add(node.groupId)
                    else -> {
                        val task = node.userObject as? Task
                        if (task != null) expandedIds.add(task.id)
                    }
                }
            }
        }
        return expandedIds
    }

    /**
     * For backward compatibility — delegates to getExpandedIdsFromTree.
     */
    fun getExpandedTaskIdsFromTree(): Set<String> = getExpandedIdsFromTree()

    /**
     * Restores expansion state from a set of IDs (task IDs and group header IDs).
     */
    fun restoreExpandedState(expandedIds: Set<String>) {
        val root = tree.model.root as? CheckedTreeNode ?: return

        traverseNodes(root) { node ->
            when (node) {
                is GroupHeaderNode -> {
                    // Group headers are expanded by default if not explicitly in the set
                    // or if they are in the set
                    if (node.groupId in expandedIds || !expandedIds.contains(node.groupId)) {
                        tree.expandPath(TreePath(node.path))
                    }
                }
                else -> {
                    val task = node.userObject as? Task
                    if (task != null && task.id in expandedIds) {
                        tree.expandPath(TreePath(node.path))
                    }
                }
            }
        }
    }

    fun ensureTaskExpanded(taskId: String) {
        val expandedIds = taskService.getExpandedTaskIds().toMutableSet()
        expandedIds.add(taskId)
        taskService.setExpandedTaskIds(expandedIds)
    }

    fun selectTaskById(taskId: String) {
        val root = tree.model.root as? CheckedTreeNode ?: return
        val path = findPathToTask(root, taskId)
        if (path != null) {
            tree.selectionPath = path
            scrollPathToVisibleVerticalOnly(path)
        }
    }

    fun scrollToTaskById(taskId: String) {
        val root = tree.model.root as? CheckedTreeNode ?: return
        val path = findPathToTask(root, taskId)
        if (path != null) {
            scrollPathToVisibleVerticalOnly(path)
        }
    }

    private fun scrollPathToVisibleVerticalOnly(path: TreePath) {
        val bounds = tree.getPathBounds(path) ?: return
        val visibleRect = tree.visibleRect

        tree.scrollRectToVisible(java.awt.Rectangle(
            0,
            bounds.y,
            visibleRect.width,
            bounds.height
        ))
    }

    fun findPathToTask(node: CheckedTreeNode, taskId: String): TreePath? {
        val task = node.userObject as? Task
        if (task?.id == taskId) {
            return TreePath(node.path)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            val path = findPathToTask(child, taskId)
            if (path != null) return path
        }
        return null
    }

    // =============== Generic Tree Traversal Utilities ===============

    private fun traverseNodes(node: CheckedTreeNode, action: (CheckedTreeNode) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            traverseNodes(child, action)
        }
    }

    private fun traverseNodesPostOrder(node: CheckedTreeNode, action: (CheckedTreeNode) -> Unit) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            traverseNodesPostOrder(child, action)
        }
        action(node)
    }
}
