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
    }

    // Current drag state
    private var draggedTask: Task? = null

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
            val task = node.userObject as? Task
                ?: return@createDefaultDragGestureRecognizer

            draggedTask = task
            try {
                dge.startDrag(DragSource.DefaultMoveDrop, TaskTransferable(task))
            } catch (_: InvalidDnDOperationException) {
                draggedTask = null
            }
        }
    }

    private fun setupDropTarget() {
        DropTarget(tree, DnDConstants.ACTION_MOVE, object : DropTargetListener {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (dtde.isDataFlavorSupported(TASK_DATA_FLAVOR)) {
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
        val sourceTask = draggedTask

        val upperThreshold = rowHeight / ChecklistConstants.DROP_ZONE_UPPER_DIVISOR
        val lowerThreshold = (rowHeight * ChecklistConstants.DROP_ZONE_LOWER_FRACTION).toInt()

        return when {
            sourceTask != null && targetTask != null && isDescendant(sourceTask, targetTask) ->
                DropPosition.NONE
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
        val sourceTask = draggedTask ?: run {
            clearDropIndicator()
            dtde.rejectDrop()
            return
        }

        dtde.acceptDrop(DnDConstants.ACTION_MOVE)

        val location = dtde.location
        val targetPath = tree.getPathForLocation(location.x, location.y)

        if (targetPath == null || targetPath.pathCount <= 1) {
            handleDropAtRoot(sourceTask, location.x, location.y)
        } else {
            handleDropOnTask(sourceTask, targetPath, location.y)
        }

        val taskIdToSelect = sourceTask.id
        draggedTask = null
        clearDropIndicator()
        dtde.dropComplete(true)
        onTaskMoved(taskIdToSelect)
    }

    private fun handleDropAtRoot(sourceTask: Task, x: Int, y: Int) {
        val dropRow = tree.getRowForLocation(x, y)
        val targetIndex = calculateRootDropIndex(dropRow)
        taskService.moveTask(sourceTask.id, null, targetIndex)
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

    private fun handleDropOnTask(sourceTask: Task, targetPath: TreePath, locationY: Int) {
        val targetNode = targetPath.lastPathComponent as? CheckedTreeNode ?: return
        val targetTask = targetNode.userObject as? Task ?: return

        if (targetTask.id == sourceTask.id || isDescendant(sourceTask, targetTask)) return

        val targetRow = tree.getRowForPath(targetPath)
        val rowBounds = tree.getRowBounds(targetRow) ?: return
        val dropY = locationY - rowBounds.y
        val rowHeight = rowBounds.height

        val upperThreshold = rowHeight / ChecklistConstants.DROP_ZONE_UPPER_DIVISOR
        val lowerThreshold = (rowHeight * ChecklistConstants.DROP_ZONE_LOWER_FRACTION).toInt()

        when {
            dropY < upperThreshold -> moveTaskAsSibling(sourceTask, targetTask, targetPath, 0)
            dropY > lowerThreshold -> moveTaskAsSibling(sourceTask, targetTask, targetPath, 1)
            targetTask.canAddSubtask() -> moveTaskAsChild(sourceTask, targetTask)
        }
    }

    private fun moveTaskAsSibling(sourceTask: Task, targetTask: Task, targetPath: TreePath, indexOffset: Int) {
        val parentTask = getParentTaskFromPath(targetPath)
        val targetIndex = getTaskIndex(targetTask, parentTask) + indexOffset
        taskService.moveTask(sourceTask.id, parentTask?.id, targetIndex)
    }

    private fun moveTaskAsChild(sourceTask: Task, targetTask: Task) {
        taskService.moveTask(sourceTask.id, targetTask.id, 0)
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

    private class TaskTransferable(private val task: Task) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(TASK_DATA_FLAVOR)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == TASK_DATA_FLAVOR
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return task
        }
    }
}
