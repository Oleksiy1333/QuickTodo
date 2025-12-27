package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.*
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.BasicStroke
import java.awt.RenderingHints

private enum class DropPosition {
    ABOVE, BELOW, AS_CHILD, NONE
}

class ChecklistPanel(private val project: Project) {

    companion object {
        private val TASK_DATA_FLAVOR = DataFlavor(Task::class.java, "Task")
    }

    private val taskService = TaskService.getInstance(project)
    private lateinit var tree: CheckboxTree
    private val mainPanel = JPanel(BorderLayout())
    private var draggedTask: Task? = null

    // Drop indicator state
    private var dropTargetRow: Int = -1
    private var dropPosition: DropPosition = DropPosition.NONE

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        tree = createCheckboxTree()
        refreshTree()

        val toolbarDecorator = ToolbarDecorator.createDecorator(tree)
            .setAddAction { addTask() }
            .setRemoveAction { removeSelectedTask() }
            .setEditAction { editSelectedTask() }
            .setEditActionUpdater {
                val selectedNode = tree.lastSelectedPathComponent as? CheckedTreeNode
                selectedNode?.userObject is Task
            }
            .addExtraActions(
                object : AnAction("Add Subtask", "Add a subtask to selected task", AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) {
                        addSubtask()
                    }

                    override fun update(e: AnActionEvent) {
                        val selectedNode = tree.lastSelectedPathComponent as? CheckedTreeNode
                        val selectedTask = selectedNode?.userObject as? Task
                        e.presentation.isEnabled = selectedTask?.canAddSubtask() == true
                    }

                    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                },
                object : AnAction("Move Up", "Move selected task up", AllIcons.Actions.MoveUp) {
                    override fun actionPerformed(e: AnActionEvent) {
                        moveSelectedTask(-1)
                    }

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = canMoveSelectedTask(-1)
                    }

                    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                },
                object : AnAction("Move Down", "Move selected task down", AllIcons.Actions.MoveDown) {
                    override fun actionPerformed(e: AnActionEvent) {
                        moveSelectedTask(1)
                    }

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = canMoveSelectedTask(1)
                    }

                    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                }
            )

        val decoratorPanel = toolbarDecorator.createPanel()

        // Create right-aligned toolbar with expand/collapse and Clear Completed actions
        val expandAllAction = object : AnAction("Expand All", "Expand all tasks", AllIcons.Actions.Expandall) {
            override fun actionPerformed(e: AnActionEvent) {
                expandAllNodes(tree)
                saveExpandedState()
            }
        }
        val collapseAllAction = object : AnAction("Collapse All", "Collapse all tasks", AllIcons.Actions.Collapseall) {
            override fun actionPerformed(e: AnActionEvent) {
                collapseAllNodes(tree)
                saveExpandedState()
            }
        }
        val clearCompletedAction = object : AnAction("Clear Completed", "Remove all completed tasks", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                clearCompletedTasks()
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = hasCompletedTasks()
            }

            override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        }
        val rightActionGroup = DefaultActionGroup(expandAllAction, collapseAllAction, clearCompletedAction)
        val rightToolbar = ActionManager.getInstance().createActionToolbar("ChecklistRightToolbar", rightActionGroup, true)
        rightToolbar.targetComponent = tree

        // Modify the decorator panel to include the right toolbar in the same row
        val decoratorLayout = decoratorPanel.layout as? BorderLayout
        if (decoratorLayout != null) {
            val originalToolbar = decoratorLayout.getLayoutComponent(BorderLayout.NORTH)
            if (originalToolbar != null) {
                decoratorPanel.remove(originalToolbar)

                // Create a toolbar row with left toolbar and right-aligned Clear Completed
                val toolbarRowPanel = JPanel(BorderLayout())
                toolbarRowPanel.add(originalToolbar, BorderLayout.CENTER)
                toolbarRowPanel.add(rightToolbar.component, BorderLayout.EAST)
                toolbarRowPanel.border = JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 0, 0, 1, 0)

                decoratorPanel.add(toolbarRowPanel, BorderLayout.NORTH)
            }
        }

        mainPanel.add(decoratorPanel, BorderLayout.CENTER)
        mainPanel.border = JBUI.Borders.empty()
    }

    private fun createCheckboxTree(): CheckboxTree {
        val renderer = TaskTreeCellRenderer()

        val checkboxTree = object : CheckboxTree(renderer, CheckedTreeNode("Tasks"), CheckboxTreeBase.CheckPolicy(false, false, false, false)) {
            override fun onNodeStateChanged(node: CheckedTreeNode) {
                val task = node.userObject as? Task ?: return
                taskService.setTaskCompletion(task.id, node.isChecked)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                paintDropIndicator(g)
            }

            private fun paintDropIndicator(g: Graphics) {
                if (dropTargetRow < 0 || dropPosition == DropPosition.NONE) return

                val rowBounds = getRowBounds(dropTargetRow) ?: return
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val accentColor = JBUI.CurrentTheme.Focus.focusColor()
                g2d.color = accentColor
                g2d.stroke = BasicStroke(2f)

                val x = rowBounds.x + 16  // Offset past checkbox
                val width = this.width - x - 4

                when (dropPosition) {
                    DropPosition.ABOVE -> {
                        val y = rowBounds.y
                        g2d.drawLine(x, y, x + width, y)
                        // Draw small arrow/circle at start
                        g2d.fillOval(x - 3, y - 3, 6, 6)
                    }
                    DropPosition.BELOW -> {
                        val y = rowBounds.y + rowBounds.height
                        g2d.drawLine(x, y, x + width, y)
                        g2d.fillOval(x - 3, y - 3, 6, 6)
                    }
                    DropPosition.AS_CHILD -> {
                        // Highlight the row with a border to show "drop as child"
                        g2d.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(4f, 4f), 0f)
                        g2d.drawRoundRect(
                            rowBounds.x + 2,
                            rowBounds.y + 1,
                            rowBounds.width - 4,
                            rowBounds.height - 2,
                            6, 6
                        )
                    }
                    DropPosition.NONE -> Unit // No indicator to paint when drop is not valid
                }
            }
        }

        // Double-click to edit task, right-click for context menu
        checkboxTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !e.isPopupTrigger) {
                    val path: TreePath = checkboxTree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? CheckedTreeNode ?: return
                    val task = node.userObject as? Task ?: return

                    // Get the row bounds to find checkbox position
                    val row = checkboxTree.getRowForPath(path)
                    val rowBounds = checkboxTree.getRowBounds(row) ?: return

                    // Checkbox rectangle is ~16px, check if click is outside it
                    val clickedOnCheckbox = e.x in rowBounds.x..(rowBounds.x + 16)

                    if (!clickedOnCheckbox) {
                        editTask(task)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e, checkboxTree)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e, checkboxTree)
                }
            }
        })

        // Enable drag and drop
        checkboxTree.dragEnabled = true
        checkboxTree.dropMode = DropMode.ON_OR_INSERT
        setupDragAndDrop(checkboxTree)

        // Register Ctrl+Z / Cmd+Z for undo
        val undoAction = object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                taskService.undoRemoveTask()
            }
        }
        val ctrlZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK)
        val metaZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK)
        checkboxTree.getInputMap(JComponent.WHEN_FOCUSED).put(ctrlZ, "undoRemoveTask")
        checkboxTree.getInputMap(JComponent.WHEN_FOCUSED).put(metaZ, "undoRemoveTask")
        checkboxTree.actionMap.put("undoRemoveTask", undoAction)

        // Save expansion state when user manually expands/collapses nodes
        checkboxTree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                saveExpandedState()
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                saveExpandedState()
            }
        })

        return checkboxTree
    }

    private fun setupDragAndDrop(tree: CheckboxTree) {
        setupDragSource(tree)
        setupDropTarget(tree)
    }

    private fun setupDragSource(tree: CheckboxTree) {
        val dragSource = DragSource.getDefaultDragSource()
        dragSource.createDefaultDragGestureRecognizer(tree, DnDConstants.ACTION_MOVE) { dge ->
            val path = tree.getPathForLocation(dge.dragOrigin.x, dge.dragOrigin.y) ?: return@createDefaultDragGestureRecognizer
            val node = path.lastPathComponent as? CheckedTreeNode ?: return@createDefaultDragGestureRecognizer
            val task = node.userObject as? Task ?: return@createDefaultDragGestureRecognizer

            draggedTask = task
            try {
                dge.startDrag(DragSource.DefaultMoveDrop, TaskTransferable(task))
            } catch (_: InvalidDnDOperationException) {
                // Drag already in progress, ignore this gesture
                draggedTask = null
            }
        }
    }

    private fun setupDropTarget(tree: CheckboxTree) {
        DropTarget(tree, DnDConstants.ACTION_MOVE, object : DropTargetListener {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (dtde.isDataFlavorSupported(TASK_DATA_FLAVOR)) {
                    dtde.acceptDrag(DnDConstants.ACTION_MOVE)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragOver(dtde: DropTargetDragEvent) {
                handleDragOver(tree, dtde)
            }

            override fun dropActionChanged(dtde: DropTargetDragEvent) {
                // Action change (e.g., Ctrl key modifier) not relevant for task reordering
            }

            override fun dragExit(dte: DropTargetEvent) {
                clearDropIndicator(tree)
            }

            override fun drop(dtde: DropTargetDropEvent) {
                handleDrop(tree, dtde)
            }
        })
    }

    private fun handleDragOver(tree: CheckboxTree, dtde: DropTargetDragEvent) {
        val location = dtde.location
        val path = tree.getPathForLocation(location.x, location.y)

        if (path == null) {
            clearDropIndicator(tree)
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
        updateDropIndicator(tree, targetRow, newDropPosition)
        dtde.acceptDrag(DnDConstants.ACTION_MOVE)
    }

    private fun calculateDropPosition(path: TreePath, dropY: Int, rowHeight: Int): DropPosition {
        val targetNode = path.lastPathComponent as? CheckedTreeNode
        val targetTask = targetNode?.userObject as? Task
        val sourceTask = draggedTask

        return when {
            sourceTask != null && targetTask != null && isDescendant(sourceTask, targetTask) -> DropPosition.NONE
            dropY < rowHeight / 4 -> DropPosition.ABOVE
            dropY > rowHeight * 3 / 4 -> DropPosition.BELOW
            targetTask?.canAddSubtask() == true -> DropPosition.AS_CHILD
            else -> DropPosition.BELOW
        }
    }

    private fun updateDropIndicator(tree: CheckboxTree, targetRow: Int, newPosition: DropPosition) {
        if (dropTargetRow != targetRow || dropPosition != newPosition) {
            dropTargetRow = targetRow
            dropPosition = newPosition
            tree.repaint()
        }
    }

    private fun clearDropIndicator(tree: CheckboxTree) {
        if (dropTargetRow != -1 || dropPosition != DropPosition.NONE) {
            dropTargetRow = -1
            dropPosition = DropPosition.NONE
            tree.repaint()
        }
    }

    private fun handleDrop(tree: CheckboxTree, dtde: DropTargetDropEvent) {
        val sourceTask = draggedTask ?: run {
            clearDropIndicator(tree)
            dtde.rejectDrop()
            return
        }

        dtde.acceptDrop(DnDConstants.ACTION_MOVE)

        val location = dtde.location
        val targetPath = tree.getPathForLocation(location.x, location.y)

        if (targetPath == null || targetPath.pathCount <= 1) {
            handleDropAtRoot(tree, sourceTask, location.x, location.y)
        } else {
            handleDropOnTask(tree, sourceTask, targetPath, location.y)
        }

        draggedTask = null
        clearDropIndicator(tree)
        dtde.dropComplete(true)
    }

    private fun handleDropAtRoot(tree: CheckboxTree, sourceTask: Task, x: Int, y: Int) {
        val dropRow = tree.getRowForLocation(x, y)
        val targetIndex = calculateRootDropIndex(tree, dropRow)
        taskService.moveTask(sourceTask.id, null, targetIndex)
    }

    private fun calculateRootDropIndex(tree: CheckboxTree, dropRow: Int): Int {
        if (dropRow < 0) return taskService.getTasks().size

        val rootTasks = taskService.getTasks()
        for (i in rootTasks.indices) {
            val taskNode = (tree.model.root as CheckedTreeNode).getChildAt(i)
            val nodeRow = tree.getRowForPath(TreePath(arrayOf(tree.model.root, taskNode)))
            if (nodeRow >= dropRow) return i
        }
        return rootTasks.size
    }

    private fun handleDropOnTask(tree: CheckboxTree, sourceTask: Task, targetPath: TreePath, locationY: Int) {
        val targetNode = targetPath.lastPathComponent as? CheckedTreeNode ?: return
        val targetTask = targetNode.userObject as? Task ?: return

        if (targetTask.id == sourceTask.id || isDescendant(sourceTask, targetTask)) return

        val targetRow = tree.getRowForPath(targetPath)
        val rowBounds = tree.getRowBounds(targetRow) ?: return
        val dropY = locationY - rowBounds.y
        val rowHeight = rowBounds.height

        when {
            dropY < rowHeight / 4 -> moveTaskAsSibling(sourceTask, targetTask, targetPath, 0)
            dropY > rowHeight * 3 / 4 -> moveTaskAsSibling(sourceTask, targetTask, targetPath, 1)
            targetTask.canAddSubtask() -> moveTaskAsChild(sourceTask, targetTask)
        }
    }

    private fun moveTaskAsSibling(sourceTask: Task, targetTask: Task, targetPath: TreePath, indexOffset: Int) {
        val parentTask = getParentTaskFromPath(targetPath)
        val targetIndex = getTaskIndex(targetTask, parentTask) + indexOffset
        taskService.moveTask(sourceTask.id, parentTask?.id, targetIndex)
        selectTaskById(sourceTask.id)
    }

    private fun moveTaskAsChild(sourceTask: Task, targetTask: Task) {
        taskService.moveTask(sourceTask.id, targetTask.id, 0)
        ensureTaskExpanded(targetTask.id)
        selectTaskById(sourceTask.id)
    }

    private fun getParentTaskFromPath(path: TreePath): Task? {
        val parentPath = path.parentPath ?: return null
        val parentNode = parentPath.lastPathComponent as? CheckedTreeNode ?: return null
        return parentNode.userObject as? Task
    }

    private fun isDescendant(parent: Task, potentialChild: Task): Boolean {
        if (parent.id == potentialChild.id) return true
        for (subtask in parent.subtasks) {
            if (isDescendant(subtask, potentialChild)) return true
        }
        return false
    }

    private fun getTaskIndex(task: Task, parent: Task?): Int {
        val siblings = parent?.subtasks ?: taskService.getTasks()
        return siblings.indexOfFirst { it.id == task.id }.coerceAtLeast(0)
    }

    private fun canMoveSelectedTask(direction: Int): Boolean {
        val selectedNode = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return false
        val selectedTask = selectedNode.userObject as? Task ?: return false
        val parentNode = selectedNode.parent as? CheckedTreeNode ?: return false
        val parentTask = parentNode.userObject as? Task

        val siblings = parentTask?.subtasks ?: taskService.getTasks()

        val currentIndex = siblings.indexOfFirst { it.id == selectedTask.id }
        if (currentIndex < 0) return false

        val newIndex = currentIndex + direction
        return newIndex >= 0 && newIndex < siblings.size
    }

    private fun moveSelectedTask(direction: Int) {
        val selectedNode = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return
        val selectedTask = selectedNode.userObject as? Task ?: return
        val parentNode = selectedNode.parent as? CheckedTreeNode ?: return
        val parentTask = parentNode.userObject as? Task

        val siblings = parentTask?.subtasks ?: taskService.getTasks()

        val currentIndex = siblings.indexOfFirst { it.id == selectedTask.id }
        if (currentIndex < 0) return

        val newIndex = currentIndex + direction
        if (newIndex < 0 || newIndex >= siblings.size) return

        val taskIdToSelect = selectedTask.id
        taskService.moveTask(selectedTask.id, parentTask?.id, newIndex)
        selectTaskById(taskIdToSelect)
    }

    private fun selectTaskById(taskId: String) {
        val path = findPathToTask(tree.model.root as CheckedTreeNode, taskId)
        if (path != null) {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun findPathToTask(node: CheckedTreeNode, taskId: String): TreePath? {
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

    private class TaskTransferable(private val task: Task) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(TASK_DATA_FLAVOR)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == TASK_DATA_FLAVOR
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return task
        }
    }

    private fun showContextMenu(e: MouseEvent, tree: CheckboxTree) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? CheckedTreeNode ?: return
        val task = node.userObject as? Task ?: return

        // Select the node that was right-clicked
        tree.selectionPath = path

        // Create action group for context menu
        val actionGroup = DefaultActionGroup()

        // "Set Priority" submenu
        val priorityGroup = DefaultActionGroup("Set Priority", true)
        priorityGroup.templatePresentation.icon = QuickTodoIcons.getIconForPriority(Priority.HIGH)

        Priority.entries.forEach { priority ->
            val action = object : AnAction(
                priority.displayName,
                "Set priority to ${priority.displayName}",
                QuickTodoIcons.getIconForPriority(priority)
            ), Toggleable {
                override fun actionPerformed(e: AnActionEvent) {
                    taskService.setTaskPriority(task.id, priority)
                }

                override fun update(e: AnActionEvent) {
                    val isSelected = task.getPriorityEnum() == priority
                    Toggleable.setSelected(e.presentation, isSelected)
                }

                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            }
            priorityGroup.add(action)
        }

        actionGroup.add(priorityGroup)
        actionGroup.add(Separator.getInstance())

        // Edit action
        val editAction = object : AnAction("Edit Task", "Edit task text", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                editTask(task)
            }
        }
        actionGroup.add(editAction)

        // Delete action
        val deleteAction = object : AnAction("Delete Task", "Delete this task", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                taskService.removeTask(task.id)
            }
        }
        actionGroup.add(deleteAction)

        // Show popup menu using ActionManager
        val popupMenu = ActionManager.getInstance().createActionPopupMenu("TaskContextMenu", actionGroup)
        popupMenu.component.show(tree, e.x, e.y)
    }

    private fun editSelectedTask() {
        val selectedNode = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return
        val selectedTask = selectedNode.userObject as? Task ?: return
        editTask(selectedTask)
    }

    private fun editTask(task: Task) {
        val newText = Messages.showInputDialog(
            project,
            "Edit task:",
            "Edit Task",
            null,
            task.text,
            null
        )
        if (newText != null && newText.isNotBlank() && newText != task.text) {
            taskService.updateTaskText(task.id, newText)
        }
    }

    private fun refreshTree() {
        // Get current UI state and merge with persisted state
        val currentExpandedIds = getExpandedTaskIdsFromTree()
        val persistedExpandedIds = taskService.getExpandedTaskIds()
        // Merge both sets - persisted state may have new expansions (e.g., when adding subtask)
        val expandedTaskIds = currentExpandedIds + persistedExpandedIds
        val isFirstLoad = expandedTaskIds.isEmpty() && taskService.getTasks().isNotEmpty()

        val tasks = taskService.getTasks()
        val rootNode = CheckedTreeNode("Tasks")

        tasks.forEach { task ->
            rootNode.add(createTaskNode(task))
        }

        tree.model = DefaultTreeModel(rootNode)

        // Restore expanded state, or expand all on first load
        if (isFirstLoad) {
            expandAllNodes(tree)
            saveExpandedState()
        } else {
            restoreExpandedState(expandedTaskIds)
        }
    }

    private fun getExpandedTaskIdsFromTree(): Set<String> {
        val expandedIds = mutableSetOf<String>()
        val root = tree.model.root as? CheckedTreeNode ?: return expandedIds
        collectExpandedIds(root, expandedIds)
        return expandedIds
    }

    private fun saveExpandedState() {
        val expandedIds = getExpandedTaskIdsFromTree()
        taskService.setExpandedTaskIds(expandedIds)
    }

    private fun collectExpandedIds(node: CheckedTreeNode, expandedIds: MutableSet<String>) {
        val task = node.userObject as? Task
        if (task != null && tree.isExpanded(TreePath(node.path))) {
            expandedIds.add(task.id)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            collectExpandedIds(child, expandedIds)
        }
    }

    private fun restoreExpandedState(expandedTaskIds: Set<String>) {
        val root = tree.model.root as? CheckedTreeNode ?: return
        restoreExpandedStateRecursively(root, expandedTaskIds)
    }

    private fun restoreExpandedStateRecursively(node: CheckedTreeNode, expandedTaskIds: Set<String>) {
        val task = node.userObject as? Task
        if (task != null && task.id in expandedTaskIds) {
            tree.expandPath(TreePath(node.path))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            restoreExpandedStateRecursively(child, expandedTaskIds)
        }
    }

    private fun createTaskNode(task: Task): CheckedTreeNode {
        val node = CheckedTreeNode(task)
        node.isChecked = task.isCompleted
        task.subtasks.forEach { subtask ->
            node.add(createTaskNode(subtask))
        }
        return node
    }

    private fun expandAllNodes(tree: JTree) {
        val root = tree.model.root as? CheckedTreeNode ?: return
        expandNodeRecursively(tree, root)
    }

    private fun expandNodeRecursively(tree: JTree, node: CheckedTreeNode) {
        val path = TreePath(node.path)
        tree.expandPath(path)
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            expandNodeRecursively(tree, child)
        }
    }

    private fun collapseAllNodes(tree: JTree) {
        val root = tree.model.root as? CheckedTreeNode ?: return
        collapseNodeRecursively(tree, root)
    }

    private fun collapseNodeRecursively(tree: JTree, node: CheckedTreeNode) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            collapseNodeRecursively(tree, child)
        }
        // Don't collapse the root node itself
        if (node.parent != null) {
            val path = TreePath(node.path)
            tree.collapsePath(path)
        }
    }

    private fun addTask() {
        val dialog = NewTaskDialog(project)
        if (dialog.showAndGet()) {
            val text = dialog.getTaskText()
            val priority = dialog.getSelectedPriority()
            if (text.isNotBlank()) {
                val task = taskService.addTask(text, priority)
                selectTaskById(task.id)
            }
        }
    }

    private fun addSubtask() {
        val selectedNode = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return
        val selectedTask = selectedNode.userObject as? Task ?: return

        if (!selectedTask.canAddSubtask()) {
            Messages.showWarningDialog(
                project,
                "Maximum nesting level (3) reached. Cannot add more subtasks.",
                "Cannot Add Subtask"
            )
            return
        }

        val dialog = NewTaskDialog(project, "New Subtask")
        if (dialog.showAndGet()) {
            val text = dialog.getTaskText()
            val priority = dialog.getSelectedPriority()
            if (text.isNotBlank()) {
                // Ensure parent will be expanded to show the new subtask
                ensureTaskExpanded(selectedTask.id)
                val subtask = taskService.addSubtask(selectedTask.id, text, priority)
                if (subtask != null) {
                    selectTaskById(subtask.id)
                }
            }
        }
    }

    private fun ensureTaskExpanded(taskId: String) {
        val expandedIds = taskService.getExpandedTaskIds().toMutableSet()
        expandedIds.add(taskId)
        taskService.setExpandedTaskIds(expandedIds)
    }

    private fun removeSelectedTask() {
        val selectedNode = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return
        val selectedTask = selectedNode.userObject as? Task ?: return
        taskService.removeTask(selectedTask.id)
    }

    private fun hasCompletedTasks(): Boolean {
        return taskService.getTasks().any { hasCompletedTasksRecursive(it) }
    }

    private fun hasCompletedTasksRecursive(task: Task): Boolean {
        if (task.isCompleted) return true
        return task.subtasks.any { hasCompletedTasksRecursive(it) }
    }

    private fun clearCompletedTasks() {
        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to remove all completed tasks?",
            "Clear Completed Tasks",
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            taskService.clearCompletedTasks()
        }
    }

    private fun setupListeners() {
        taskService.addListener { refreshTree() }
    }

    fun getContent(): JPanel = mainPanel
}
