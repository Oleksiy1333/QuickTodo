package com.oleksiy.quicktodo.ui.dnd

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.ChecklistConstants
import com.oleksiy.quicktodo.ui.DropPosition
import com.intellij.ui.CheckedTreeNode
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.Rectangle
import java.awt.dnd.*
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.tree.TreePath

private typealias TaskList = List<Task>

/**
 * Handles all drag and drop operations for the task tree.
 */
class TaskDragDropHandler(
    private val tree: JTree,
    private val taskService: TaskService,
    private val onTaskMoved: (taskId: String) -> Unit,
    private val ensureTaskExpanded: (taskId: String) -> Unit
) {
    companion object {
        val TASK_DATA_FLAVOR = DataFlavor(Task::class.java, "Task")
        val TASKS_DATA_FLAVOR = DataFlavor(TaskList::class.java, "Tasks")

        /** Height of the virtual drop zone below the last row (pixels) */
        private const val DROP_BELOW_LAST_ZONE_HEIGHT = 30
    }

    // Current drag state - supports multiple selected tasks
    private var draggedTasks: List<Task> = emptyList()

    var dropTargetRow: Int = -1
        private set
    var dropPosition: DropPosition = DropPosition.NONE
        private set

    // Cache target task to survive tree rebuilds
    private var dropTargetTask: Task? = null

    // Auto-scroll state
    private var autoScrollDirection: Int = 0
    private val autoScrollTimer = Timer(ChecklistConstants.AUTO_SCROLL_DELAY_MS) {
        performAutoScroll()
    }

    // Hover-to-expand state
    private var hoverExpandRow: Int = -1
    private val hoverExpandTimer = Timer(ChecklistConstants.HOVER_EXPAND_DELAY_MS) {
        expandHoveredNode()
    }.apply { isRepeats = false }

    fun setup() {
        setupDragSource()
        setupDropTarget()
    }

    fun clearDropIndicator() {
        if (dropTargetRow != -1 || dropPosition != DropPosition.NONE) {
            dropTargetRow = -1
            dropPosition = DropPosition.NONE
            dropTargetTask = null
            tree.repaint()
        }
        stopAutoScroll()
        stopHoverExpand()
    }

    private fun updateAutoScroll(locationY: Int) {
        val visibleRect = tree.visibleRect
        val scrollZone = ChecklistConstants.AUTO_SCROLL_ZONE_HEIGHT

        autoScrollDirection = when {
            locationY < visibleRect.y + scrollZone -> -1  // Scroll up
            locationY > visibleRect.y + visibleRect.height - scrollZone -> 1  // Scroll down
            else -> 0
        }

        if (autoScrollDirection != 0 && !autoScrollTimer.isRunning) {
            autoScrollTimer.start()
        } else if (autoScrollDirection == 0 && autoScrollTimer.isRunning) {
            autoScrollTimer.stop()
        }
    }

    private fun performAutoScroll() {
        if (autoScrollDirection == 0) return

        val visibleRect = tree.visibleRect
        val scrollIncrement = ChecklistConstants.AUTO_SCROLL_INCREMENT
        val newY = visibleRect.y + (autoScrollDirection * scrollIncrement)

        // Preserve horizontal scroll position (x = 0) to prevent unwanted horizontal scrolling
        tree.scrollRectToVisible(Rectangle(
            0,
            newY.coerceAtLeast(0),
            visibleRect.width,
            visibleRect.height
        ))
    }

    private fun stopAutoScroll() {
        autoScrollDirection = 0
        if (autoScrollTimer.isRunning) {
            autoScrollTimer.stop()
        }
    }

    private fun updateHoverExpand(targetRow: Int, path: TreePath?) {
        // Only trigger expand for collapsed nodes with children
        if (targetRow < 0 || path == null) {
            stopHoverExpand()
            return
        }

        val isCollapsed = tree.isCollapsed(path)
        val node = path.lastPathComponent as? CheckedTreeNode
        val hasChildren = node != null && node.childCount > 0

        if (isCollapsed && hasChildren) {
            if (hoverExpandRow != targetRow) {
                // Started hovering over a new collapsed node
                hoverExpandRow = targetRow
                hoverExpandTimer.restart()
            }
        } else {
            stopHoverExpand()
        }
    }

    private fun expandHoveredNode() {
        if (hoverExpandRow >= 0) {
            val path = tree.getPathForRow(hoverExpandRow)
            if (path != null && tree.isCollapsed(path)) {
                tree.expandPath(path)
            }
        }
        hoverExpandRow = -1
    }

    private fun stopHoverExpand() {
        hoverExpandRow = -1
        if (hoverExpandTimer.isRunning) {
            hoverExpandTimer.stop()
        }
    }

    private fun setupDragSource() {
        val dragSource = DragSource.getDefaultDragSource()
        dragSource.createDefaultDragGestureRecognizer(tree, DnDConstants.ACTION_MOVE) { dge ->
            // Use getClosestRowForLocation to detect row even when clicking on empty space within the row
            val row = tree.getClosestRowForLocation(dge.dragOrigin.x, dge.dragOrigin.y)
            if (row < 0) return@createDefaultDragGestureRecognizer

            val rowBounds = tree.getRowBounds(row)
            if (rowBounds == null || dge.dragOrigin.y < rowBounds.y || dge.dragOrigin.y >= rowBounds.y + rowBounds.height) {
                return@createDefaultDragGestureRecognizer
            }

            val path = tree.getPathForRow(row)
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
        return tasks.filter { task ->
            // Keep task only if none of its ancestors are in the selection
            !tasks.any { other -> other.id != task.id && taskService.isAncestorOf(other.id, task.id) }
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
        updateAutoScroll(location.y)

        // Use getClosestRowForLocation to detect row even when mouse is on empty space
        val targetRow = tree.getClosestRowForLocation(location.x, location.y)
        if (targetRow < 0) {
            clearDropIndicator()
            dtde.acceptDrag(DnDConstants.ACTION_MOVE)
            return
        }

        val rowBounds = tree.getRowBounds(targetRow)
        if (rowBounds == null || location.y < rowBounds.y) {
            clearDropIndicator()
            dtde.acceptDrag(DnDConstants.ACTION_MOVE)
            return
        }

        // Check if mouse is below the last row - show "drop at end" indicator
        val lastRow = tree.rowCount - 1
        if (targetRow == lastRow && location.y >= rowBounds.y + rowBounds.height) {
            val lastRowBounds = tree.getRowBounds(lastRow)
            if (lastRowBounds != null &&
                location.y < lastRowBounds.y + lastRowBounds.height + DROP_BELOW_LAST_ZONE_HEIGHT) {
                // Show drop indicator below last row
                val path = tree.getPathForRow(lastRow)
                val lastNode = path?.lastPathComponent as? CheckedTreeNode
                val lastTask = lastNode?.userObject as? Task
                updateDropIndicator(lastRow, DropPosition.BELOW, lastTask)
                updateHoverExpand(lastRow, path)
                dtde.acceptDrag(DnDConstants.ACTION_MOVE)
                return
            }
            clearDropIndicator()
            dtde.acceptDrag(DnDConstants.ACTION_MOVE)
            return
        }

        if (location.y >= rowBounds.y + rowBounds.height) {
            // Mouse is outside actual row bounds but not in "below last" zone
            clearDropIndicator()
            dtde.acceptDrag(DnDConstants.ACTION_MOVE)
            return
        }

        val path = tree.getPathForRow(targetRow)
        if (path == null) {
            clearDropIndicator()
            dtde.acceptDrag(DnDConstants.ACTION_MOVE)
            return
        }

        val targetNode = path.lastPathComponent as? CheckedTreeNode
        val targetTask = targetNode?.userObject as? Task

        val newDropPosition = calculateDropPosition(path, location.y - rowBounds.y, rowBounds.height)
        updateDropIndicator(targetRow, newDropPosition, targetTask)
        updateHoverExpand(targetRow, path)
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
            sourceTasks.any { taskService.isAncestorOf(it.id, targetTask.id) }

        return when {
            hasCircularDependency -> DropPosition.NONE
            dropY < upperThreshold -> DropPosition.ABOVE
            dropY > lowerThreshold -> DropPosition.BELOW
            targetTask?.canAddSubtask() == true -> DropPosition.AS_CHILD
            else -> DropPosition.BELOW
        }
    }

    private fun updateDropIndicator(targetRow: Int, newPosition: DropPosition, targetTask: Task?) {
        if (dropTargetRow != targetRow || dropPosition != newPosition) {
            dropTargetRow = targetRow
            dropPosition = newPosition
            dropTargetTask = targetTask
            tree.repaint()
        } else if (dropTargetTask?.id != targetTask?.id) {
            // Same row but different task (tree was rebuilt)
            dropTargetTask = targetTask
        }
    }

    private fun handleDrop(dtde: DropTargetDropEvent) {
        val sourceTasks = draggedTasks.takeIf { it.isNotEmpty() } ?: run {
            clearDropIndicator()
            dtde.rejectDrop()
            return
        }

        // Use the cached drop state that was shown to the user
        val cachedPosition = dropPosition
        val cachedRow = dropTargetRow
        val cachedTarget = dropTargetTask

        dtde.acceptDrop(DnDConstants.ACTION_MOVE)

        when {
            cachedRow < 0 || cachedTarget == null -> {
                handleDropAtRoot(sourceTasks, dtde.location.x, dtde.location.y)
            }
            cachedPosition == DropPosition.NONE -> {
                // Invalid drop position - do nothing
            }
            else -> {
                handleDropOnTask(sourceTasks, cachedTarget, cachedPosition)
            }
        }

        // Select the first moved task
        val taskIdToSelect = sourceTasks.first().id
        draggedTasks = emptyList()
        clearDropIndicator()
        dtde.dropComplete(true)
        onTaskMoved(taskIdToSelect)
    }

    private fun handleDropAtRoot(sourceTasks: List<Task>, x: Int, y: Int) {
        // Use getClosestRowForLocation for consistency with dragOver
        val dropRow = tree.getClosestRowForLocation(x, y)
        val targetIndex = calculateRootDropIndex(dropRow, y)
        taskService.moveTasks(sourceTasks.map { it.id }, null, targetIndex)
    }

    private fun calculateRootDropIndex(dropRow: Int, y: Int): Int {
        if (dropRow < 0) return taskService.getTasks().size

        // Check if dropping below the last row
        val rowBounds = tree.getRowBounds(dropRow)
        if (rowBounds != null && y >= rowBounds.y + rowBounds.height) {
            // Mouse is below this row - append to end
            return taskService.getTasks().size
        }

        val rootTasks = taskService.getTasks()
        for (i in rootTasks.indices) {
            val root = tree.model.root as? CheckedTreeNode ?: return rootTasks.size
            if (i >= root.childCount) continue
            val taskNode = root.getChildAt(i)
            val nodeRow = tree.getRowForPath(TreePath(arrayOf(tree.model.root, taskNode)))
            if (nodeRow >= dropRow) return i
        }
        return rootTasks.size
    }

    private fun handleDropOnTask(sourceTasks: List<Task>, targetTask: Task, position: DropPosition) {
        // Verify target task still exists in TaskService
        val verifiedTarget = taskService.findTask(targetTask.id) ?: return

        // Check for circular dependency or self-drop
        val sourceIds = sourceTasks.map { it.id }.toSet()
        if (sourceIds.contains(verifiedTarget.id) ||
            sourceIds.any { taskService.isAncestorOf(it, verifiedTarget.id) }) return

        when (position) {
            DropPosition.ABOVE -> moveTasksAsSibling(sourceTasks, verifiedTarget, 0)
            DropPosition.BELOW -> moveTasksAsSibling(sourceTasks, verifiedTarget, 1)
            DropPosition.AS_CHILD -> moveTasksAsChild(sourceTasks, verifiedTarget)
            DropPosition.NONE -> { /* Should not happen, handled in handleDrop */ }
        }
    }

    private fun moveTasksAsSibling(sourceTasks: List<Task>, targetTask: Task, indexOffset: Int) {
        val parentId = taskService.findParentId(targetTask.id)
        val parentTask = parentId?.let { taskService.findTask(it) }
        val targetIndex = getTaskIndex(targetTask, parentTask) + indexOffset
        taskService.moveTasks(sourceTasks.map { it.id }, parentId, targetIndex)
    }

    private fun moveTasksAsChild(sourceTasks: List<Task>, targetTask: Task) {
        taskService.moveTasks(sourceTasks.map { it.id }, targetTask.id, 0)
        ensureTaskExpanded(targetTask.id)
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
