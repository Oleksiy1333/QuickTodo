package com.oleksiy.quicktodo.ui.dnd

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.ChecklistConstants
import com.oleksiy.quicktodo.ui.DropPosition
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.*
import javax.swing.tree.TreePath

private typealias TaskList = List<Task>

/**
 * Handles all drag and drop operations for the task tree.
 */
class TaskDragDropHandler(
    private val tree: CheckboxTree,
    private val taskService: TaskService,
    private val onTaskMoved: (taskId: String) -> Unit,
    private val ensureTaskExpanded: (taskId: String) -> Unit
) {
    companion object {
        val TASK_DATA_FLAVOR = DataFlavor(Task::class.java, "Task")
        val TASKS_DATA_FLAVOR = DataFlavor(TaskList::class.java, "Tasks")
    }

    // Current drag state - supports multiple selected tasks
    private var draggedTasks: List<Task> = emptyList()

    var dropTargetRow: Int = -1
        private set
    var dropPosition: DropPosition = DropPosition.NONE
        private set

    fun setup() {
        setupDragSource()
        setupDropTarget()
    }

    fun clearDropIndicator() {
        if (dropTargetRow != -1 || dropPosition != DropPosition.NONE) {
            dropTargetRow = -1
            dropPosition = DropPosition.NONE
            tree.repaint()
        }
    }

    private fun setupDragSource() {
        val dragSource = DragSource.getDefaultDragSource()
        dragSource.createDefaultDragGestureRecognizer(tree, DnDConstants.ACTION_MOVE) { dge ->
            val path = tree.getPathForLocation(dge.dragOrigin.x, dge.dragOrigin.y)
                ?: return@createDefaultDragGestureRecognizer
            val node = path.lastPathComponent as? CheckedTreeNode
                ?: return@createDefaultDragGestureRecognizer
            val clickedTask = node.userObject as? Task
                ?: return@createDefaultDragGestureRecognizer

            // Collect all selected tasks, or just the clicked task if not in selection
            val selectedTasks = getSelectedTasks()
            val tasksToMove = if (selectedTasks.any { it.id == clickedTask.id }) {
                // Filter out tasks that are descendants of other selected tasks
                filterOutDescendants(selectedTasks)
            } else {
                listOf(clickedTask)
            }

            if (tasksToMove.isEmpty()) return@createDefaultDragGestureRecognizer

            draggedTasks = tasksToMove
            try {
                dge.startDrag(DragSource.DefaultMoveDrop, TasksTransferable(tasksToMove))
            } catch (_: InvalidDnDOperationException) {
                draggedTasks = emptyList()
            }
        }
    }

    private fun getSelectedTasks(): List<Task> {
        return tree.selectionPaths?.mapNotNull { path ->
            (path.lastPathComponent as? CheckedTreeNode)?.userObject as? Task
        } ?: emptyList()
    }

    private fun filterOutDescendants(tasks: List<Task>): List<Task> {
        val taskIds = tasks.map { it.id }.toSet()
        return tasks.filter { task ->
            // Keep task only if none of its ancestors are in the selection
            !tasks.any { other -> other.id != task.id && isDescendant(other, task) }
        }
    }

    private fun setupDropTarget() {
        DropTarget(tree, DnDConstants.ACTION_MOVE, object : DropTargetListener {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (dtde.isDataFlavorSupported(TASKS_DATA_FLAVOR)) {
                    dtde.acceptDrag(DnDConstants.ACTION_MOVE)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragOver(dtde: DropTargetDragEvent) = handleDragOver(dtde)
            override fun dropActionChanged(dtde: DropTargetDragEvent) = Unit
            override fun dragExit(dte: DropTargetEvent) = clearDropIndicator()
            override fun drop(dtde: DropTargetDropEvent) = handleDrop(dtde)
        })
    }

    private fun handleDragOver(dtde: DropTargetDragEvent) {
        val location = dtde.location
        val path = tree.getPathForLocation(location.x, location.y)

        if (path == null) {
            clearDropIndicator()
            dtde.acceptDrag(DnDConstants.ACTION_MOVE)
            return
        }

        val targetRow = tree.getRowForPath(path)
        val rowBounds = tree.getRowBounds(targetRow)
        if (rowBounds == null) {
            dtde.acceptDrag(DnDConstants.ACTION_MOVE)
            return
        }

        val newDropPosition = calculateDropPosition(path, location.y - rowBounds.y, rowBounds.height)
        updateDropIndicator(targetRow, newDropPosition)
        dtde.acceptDrag(DnDConstants.ACTION_MOVE)
    }

    private fun calculateDropPosition(path: TreePath, dropY: Int, rowHeight: Int): DropPosition {
        val targetNode = path.lastPathComponent as? CheckedTreeNode
        val targetTask = targetNode?.userObject as? Task
        val sourceTasks = draggedTasks

        val upperThreshold = rowHeight / ChecklistConstants.DROP_ZONE_UPPER_DIVISOR
        val lowerThreshold = (rowHeight * ChecklistConstants.DROP_ZONE_LOWER_FRACTION).toInt()

        // Check if any source task is an ancestor of the target (would create circular dependency)
        val hasCircularDependency = sourceTasks.isNotEmpty() && targetTask != null &&
            sourceTasks.any { isDescendant(it, targetTask) }

        return when {
            hasCircularDependency -> DropPosition.NONE
            dropY < upperThreshold -> DropPosition.ABOVE
            dropY > lowerThreshold -> DropPosition.BELOW
            targetTask?.canAddSubtask() == true -> DropPosition.AS_CHILD
            else -> DropPosition.BELOW
        }
    }

    private fun updateDropIndicator(targetRow: Int, newPosition: DropPosition) {
        if (dropTargetRow != targetRow || dropPosition != newPosition) {
            dropTargetRow = targetRow
            dropPosition = newPosition
            tree.repaint()
        }
    }

    private fun handleDrop(dtde: DropTargetDropEvent) {
        val sourceTasks = draggedTasks.takeIf { it.isNotEmpty() } ?: run {
            clearDropIndicator()
            dtde.rejectDrop()
            return
        }

        dtde.acceptDrop(DnDConstants.ACTION_MOVE)

        val location = dtde.location
        val targetPath = tree.getPathForLocation(location.x, location.y)

        if (targetPath == null || targetPath.pathCount <= 1) {
            handleDropAtRoot(sourceTasks, location.x, location.y)
        } else {
            handleDropOnTask(sourceTasks, targetPath, location.y)
        }

        // Select the first moved task
        val taskIdToSelect = sourceTasks.first().id
        draggedTasks = emptyList()
        clearDropIndicator()
        dtde.dropComplete(true)
        onTaskMoved(taskIdToSelect)
    }

    private fun handleDropAtRoot(sourceTasks: List<Task>, x: Int, y: Int) {
        val dropRow = tree.getRowForLocation(x, y)
        val targetIndex = calculateRootDropIndex(dropRow)
        taskService.moveTasks(sourceTasks.map { it.id }, null, targetIndex)
    }

    private fun calculateRootDropIndex(dropRow: Int): Int {
        if (dropRow < 0) return taskService.getTasks().size

        val rootTasks = taskService.getTasks()
        for (i in rootTasks.indices) {
            val taskNode = (tree.model.root as CheckedTreeNode).getChildAt(i)
            val nodeRow = tree.getRowForPath(TreePath(arrayOf(tree.model.root, taskNode)))
            if (nodeRow >= dropRow) return i
        }
        return rootTasks.size
    }

    private fun handleDropOnTask(sourceTasks: List<Task>, targetPath: TreePath, locationY: Int) {
        val targetNode = targetPath.lastPathComponent as? CheckedTreeNode ?: return
        val targetTask = targetNode.userObject as? Task ?: return

        // Check for circular dependency or self-drop
        val sourceIds = sourceTasks.map { it.id }.toSet()
        if (sourceIds.contains(targetTask.id) || sourceTasks.any { isDescendant(it, targetTask) }) return

        val targetRow = tree.getRowForPath(targetPath)
        val rowBounds = tree.getRowBounds(targetRow) ?: return
        val dropY = locationY - rowBounds.y
        val rowHeight = rowBounds.height

        val upperThreshold = rowHeight / ChecklistConstants.DROP_ZONE_UPPER_DIVISOR
        val lowerThreshold = (rowHeight * ChecklistConstants.DROP_ZONE_LOWER_FRACTION).toInt()

        when {
            dropY < upperThreshold -> moveTasksAsSibling(sourceTasks, targetTask, targetPath, 0)
            dropY > lowerThreshold -> moveTasksAsSibling(sourceTasks, targetTask, targetPath, 1)
            targetTask.canAddSubtask() -> moveTasksAsChild(sourceTasks, targetTask)
        }
    }

    private fun moveTasksAsSibling(sourceTasks: List<Task>, targetTask: Task, targetPath: TreePath, indexOffset: Int) {
        val parentTask = getParentTaskFromPath(targetPath)
        val targetIndex = getTaskIndex(targetTask, parentTask) + indexOffset
        taskService.moveTasks(sourceTasks.map { it.id }, parentTask?.id, targetIndex)
    }

    private fun moveTasksAsChild(sourceTasks: List<Task>, targetTask: Task) {
        taskService.moveTasks(sourceTasks.map { it.id }, targetTask.id, 0)
        ensureTaskExpanded(targetTask.id)
    }

    private fun getParentTaskFromPath(path: TreePath): Task? {
        val parentPath = path.parentPath ?: return null
        val parentNode = parentPath.lastPathComponent as? CheckedTreeNode ?: return null
        return parentNode.userObject as? Task
    }

    private fun isDescendant(parent: Task, potentialChild: Task): Boolean {
        if (parent.id == potentialChild.id) return true
        return parent.subtasks.any { isDescendant(it, potentialChild) }
    }

    private fun getTaskIndex(task: Task, parent: Task?): Int {
        val siblings = parent?.subtasks ?: taskService.getTasks()
        return siblings.indexOfFirst { it.id == task.id }.coerceAtLeast(0)
    }

    private class TasksTransferable(private val tasks: List<Task>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(TASKS_DATA_FLAVOR)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == TASKS_DATA_FLAVOR
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return tasks
        }
    }
}
