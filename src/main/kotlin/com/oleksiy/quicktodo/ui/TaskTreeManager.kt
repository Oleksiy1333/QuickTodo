package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.intellij.ui.CheckedTreeNode
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Manages tree state including node creation, expansion state, and task selection.
 */
class TaskTreeManager(
    private val tree: JTree,
    private val taskService: TaskService
) {
    private var isRefreshing = false

    /**
     * Returns true if the tree is currently being refreshed.
     * Used to suppress expansion state saving during programmatic changes.
     */
    fun isRefreshing(): Boolean = isRefreshing

    /**
     * Refreshes the tree with current tasks, preserving expansion and selection state.
     */
    fun refreshTree() {
        isRefreshing = true
        try {
            val expandedTaskIds = getExpandedTaskIdsFromTree() + taskService.getExpandedTaskIds()
            val selectedTaskId = getSelectedTaskId()

            val tasks = taskService.getTasks()
            val hideCompleted = taskService.isHideCompleted()
            val rootNode = CheckedTreeNode("Tasks")

            tasks.forEach { task ->
                val node = createTaskNode(task, hideCompleted)
                if (node != null) {
                    rootNode.add(node)
                }
            }

            tree.model = DefaultTreeModel(rootNode)
            restoreExpandedState(expandedTaskIds)

            if (selectedTaskId != null) {
                selectTaskById(selectedTaskId)
            }
        } finally {
            isRefreshing = false
        }
    }

    /**
     * Gets the currently selected task's ID, or null if no task is selected.
     */
    private fun getSelectedTaskId(): String? {
        val node = tree.lastSelectedPathComponent as? CheckedTreeNode
        return (node?.userObject as? Task)?.id
    }

    /**
     * Checks if a task has any incomplete descendants (subtasks, sub-subtasks, etc.).
     */
    private fun hasIncompleteDescendants(task: Task): Boolean =
        task.subtasks.any { !it.isCompleted || hasIncompleteDescendants(it) }

    /**
     * Creates a tree node for a task and its subtasks recursively.
     * Returns null if the task should be hidden (completed when hideCompleted is true
     * and has no incomplete descendants).
     */
    private fun createTaskNode(task: Task, hideCompleted: Boolean): CheckedTreeNode? {
        // Only hide completed tasks if they have no incomplete descendants
        if (hideCompleted && task.isCompleted && !hasIncompleteDescendants(task)) {
            return null
        }
        val node = CheckedTreeNode(task)
        node.isChecked = task.isCompleted
        task.subtasks.forEach { subtask ->
            val subtaskNode = createTaskNode(subtask, hideCompleted)
            if (subtaskNode != null) {
                node.add(subtaskNode)
            }
        }
        return node
    }

    /**
     * Expands all nodes in the tree.
     */
    fun expandAll() {
        val root = tree.model.root as? CheckedTreeNode ?: return
        traverseNodes(root) { node ->
            tree.expandPath(TreePath(node.path))
        }
    }

    /**
     * Collapses all nodes except the root.
     */
    fun collapseAll() {
        val root = tree.model.root as? CheckedTreeNode ?: return
        traverseNodesPostOrder(root) { node ->
            if (node.parent != null) {
                tree.collapsePath(TreePath(node.path))
            }
        }
    }

    /**
     * Saves the current expansion state to TaskService.
     */
    fun saveExpandedState() {
        val expandedIds = getExpandedTaskIdsFromTree()
        taskService.setExpandedTaskIds(expandedIds)
    }

    /**
     * Gets all expanded task IDs from the current tree state.
     */
    fun getExpandedTaskIdsFromTree(): Set<String> {
        val expandedIds = mutableSetOf<String>()
        val root = tree.model.root as? CheckedTreeNode ?: return expandedIds

        traverseNodes(root) { node ->
            val task = node.userObject as? Task
            if (task != null && tree.isExpanded(TreePath(node.path))) {
                expandedIds.add(task.id)
            }
        }
        return expandedIds
    }

    /**
     * Restores expansion state from a set of task IDs.
     */
    fun restoreExpandedState(expandedTaskIds: Set<String>) {
        val root = tree.model.root as? CheckedTreeNode ?: return

        traverseNodes(root) { node ->
            val task = node.userObject as? Task
            if (task != null && task.id in expandedTaskIds) {
                tree.expandPath(TreePath(node.path))
            }
        }
    }

    /**
     * Ensures a specific task is expanded (useful when adding subtasks).
     */
    fun ensureTaskExpanded(taskId: String) {
        val expandedIds = taskService.getExpandedTaskIds().toMutableSet()
        expandedIds.add(taskId)
        taskService.setExpandedTaskIds(expandedIds)
    }

    /**
     * Selects a task by ID and scrolls to make it visible.
     */
    fun selectTaskById(taskId: String) {
        val root = tree.model.root as? CheckedTreeNode ?: return
        val path = findPathToTask(root, taskId)
        if (path != null) {
            tree.selectionPath = path
            scrollPathToVisibleVerticalOnly(path)
        }
    }

    /**
     * Scrolls to make a task visible without changing selection.
     */
    fun scrollToTaskById(taskId: String) {
        val root = tree.model.root as? CheckedTreeNode ?: return
        val path = findPathToTask(root, taskId)
        if (path != null) {
            scrollPathToVisibleVerticalOnly(path)
        }
    }

    /**
     * Scrolls to make a path visible, but only vertically (preserves horizontal scroll position).
     */
    private fun scrollPathToVisibleVerticalOnly(path: TreePath) {
        val bounds = tree.getPathBounds(path) ?: return
        val visibleRect = tree.visibleRect

        // Only scroll vertically - keep horizontal position at 0
        tree.scrollRectToVisible(java.awt.Rectangle(
            0,
            bounds.y,
            visibleRect.width,
            bounds.height
        ))
    }

    /**
     * Finds the TreePath to a task by its ID.
     */
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

    /**
     * Traverses all nodes in pre-order (parent before children).
     */
    private fun traverseNodes(node: CheckedTreeNode, action: (CheckedTreeNode) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            traverseNodes(child, action)
        }
    }

    /**
     * Traverses all nodes in post-order (children before parent).
     * Useful for collapse operations where children must be collapsed first.
     */
    private fun traverseNodesPostOrder(node: CheckedTreeNode, action: (CheckedTreeNode) -> Unit) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            traverseNodesPostOrder(child, action)
        }
        action(node)
    }
}
