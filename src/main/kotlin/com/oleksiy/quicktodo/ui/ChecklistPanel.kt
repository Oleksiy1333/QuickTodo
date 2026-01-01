package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.action.AddSubtaskAction
import com.oleksiy.quicktodo.action.ChecklistActionCallback
import com.oleksiy.quicktodo.action.ClearCompletedAction
import com.oleksiy.quicktodo.action.CollapseAllAction
import com.oleksiy.quicktodo.action.ExpandAllAction
import com.oleksiy.quicktodo.action.MoveTaskAction
import com.oleksiy.quicktodo.action.RedoAction
import com.oleksiy.quicktodo.action.ToggleHideCompletedAction
import com.oleksiy.quicktodo.action.UndoAction
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.FocusService
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.dnd.TaskDragDropHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.AbstractAction
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Main panel for the QuickTodo checklist tool window.
 * Implements ChecklistActionCallback for toolbar actions and Disposable for cleanup.
 */
class ChecklistPanel(private val project: Project) : ChecklistActionCallback, Disposable {

    companion object {
        private val instances = mutableMapOf<Project, ChecklistPanel>()

        fun getInstance(project: Project): ChecklistPanel? = instances[project]
    }

    private val taskService = TaskService.getInstance(project)
    private val focusService = FocusService.getInstance(project)
    private lateinit var tree: TaskTree
    private lateinit var renderer: TaskTreeCellRenderer
    private lateinit var treeManager: TaskTreeManager
    private lateinit var dragDropHandler: TaskDragDropHandler
    private lateinit var contextMenuBuilder: TaskContextMenuBuilder
    private lateinit var focusBarPanel: FocusBarPanel
    private val mainPanel = JPanel(BorderLayout())
    private var taskListener: (() -> Unit)? = null
    private var focusListener: FocusService.FocusChangeListener? = null
    private val animationService = CheckmarkAnimationService()

    init {
        instances[project] = this
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        tree = createTaskTree()
        animationService.setRepaintCallback { tree.repaint() }
        treeManager = TaskTreeManager(tree, taskService)
        dragDropHandler = TaskDragDropHandler(
            tree,
            taskService,
            onTaskMoved = { taskId -> treeManager.selectTaskById(taskId) },
            ensureTaskExpanded = { taskId -> treeManager.ensureTaskExpanded(taskId) }
        )
        contextMenuBuilder = TaskContextMenuBuilder(
            project,
            taskService,
            focusService,
            onEditTask = { task -> editTask(task) },
            onAddSubtask = { task -> addSubtaskToTask(task) }
        )

        dragDropHandler.setup()
        treeManager.refreshTree()

        val toolbarDecorator = createToolbarDecorator()
        val decoratorPanel = toolbarDecorator.createPanel()
        setupRightToolbar(decoratorPanel)

        focusBarPanel = FocusBarPanel(project)
        mainPanel.add(focusBarPanel, BorderLayout.NORTH)
        mainPanel.add(decoratorPanel, BorderLayout.CENTER)
        mainPanel.border = JBUI.Borders.empty()
    }

    private fun createToolbarDecorator(): ToolbarDecorator {
        return ToolbarDecorator.createDecorator(tree)
            .setAddAction { addTask() }
            .setRemoveAction { removeSelectedTask() }
            .setEditAction { editSelectedTask() }
            .setEditActionUpdater { getSelectedTask() != null }
            .addExtraActions(
                AddSubtaskAction(this),
                MoveTaskAction(-1, this),
                MoveTaskAction(1, this)
            )
    }

    private fun setupRightToolbar(decoratorPanel: JPanel) {
        val rightActionGroup = DefaultActionGroup(
            UndoAction { taskService },
            RedoAction { taskService },
            Separator.getInstance(),
            ExpandAllAction(this),
            CollapseAllAction(this),
            ToggleHideCompletedAction(this),
            ClearCompletedAction(this)
        )
        val rightToolbar = ActionManager.getInstance()
            .createActionToolbar("ChecklistRightToolbar", rightActionGroup, true)
        rightToolbar.targetComponent = tree

        val decoratorLayout = decoratorPanel.layout as? BorderLayout ?: return
        val originalToolbar = decoratorLayout.getLayoutComponent(BorderLayout.NORTH) ?: return

        decoratorPanel.remove(originalToolbar)
        val toolbarRowPanel = JPanel(BorderLayout()).apply {
            add(originalToolbar, BorderLayout.CENTER)
            add(rightToolbar.component, BorderLayout.EAST)
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 0, 0, 1, 0)
        }
        decoratorPanel.add(toolbarRowPanel, BorderLayout.NORTH)
    }

    private fun createTaskTree(): TaskTree {
        renderer = TaskTreeCellRenderer(focusService)

        val taskTree = object : TaskTree(
            onTaskToggled = { task, isChecked -> handleTaskToggled(task, isChecked) }
        ) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)

                // Paint drop indicator during drag
                if (dragDropHandler.dropTargetRow >= 0 &&
                    dragDropHandler.dropPosition != DropPosition.NONE
                ) {
                    DropIndicatorPainter.paint(
                        g, this,
                        dragDropHandler.dropTargetRow,
                        dragDropHandler.dropPosition
                    )
                }

                // Paint checkmark animations
                if (animationService.hasActiveAnimations()) {
                    val g2 = g as? Graphics2D ?: return
                    for ((_, state) in animationService.getActiveAnimations()) {
                        CheckmarkPainter.paint(g2, state.bounds, state.getProgress())
                    }
                }
            }

            override fun getToolTipText(event: MouseEvent): String? {
                val path = getPathForRowAt(event.x, event.y) ?: return null
                val node = path.lastPathComponent as? CheckedTreeNode ?: return null
                val task = node.userObject as? Task ?: return null

                if (task.hasCodeLocation() && isMouseOverLocationLink(event)) {
                    return task.codeLocation?.relativePath
                }
                return null
            }
        }

        taskTree.cellRenderer = renderer
        taskTree.isRootVisible = false
        taskTree.showsRootHandles = true

        // Enable tooltips
        javax.swing.ToolTipManager.sharedInstance().registerComponent(taskTree)

        setupMouseListeners(taskTree)
        setupKeyboardShortcuts(taskTree)
        setupExpansionListener(taskTree)

        taskTree.dragEnabled = true
        taskTree.dropMode = DropMode.ON_OR_INSERT

        return taskTree
    }

    /**
     * Handle task checkbox toggle from TaskTree.
     */
    private fun handleTaskToggled(task: Task, isChecked: Boolean) {
        // Trigger animation before state change if completing
        if (isChecked) {
            val path = treeManager.findPathToTask(
                tree.model.root as CheckedTreeNode,
                task.id
            )
            if (path != null) {
                val row = tree.getRowForPath(path)
                if (row >= 0) {
                    tree.getRowBounds(row)?.let { bounds ->
                        animationService.startAnimation(task.id, bounds)
                    }
                }
            }
        }

        taskService.setTaskCompletion(task.id, isChecked)
        if (isChecked) {
            focusService.onTaskCompleted(task.id)
        }
    }

    private fun setupMouseListeners(tree: TaskTree) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Skip if click was on checkbox (handled by TaskTree)
                if (tree.isOverCheckbox(e.x, e.y)) {
                    return
                }

                if (e.clickCount == 1 && e.button == MouseEvent.BUTTON1) {
                    if (handleLocationClick(e)) {
                        return
                    }
                    // Toggle selection if task was already selected before the click
                    // (pathWasSelectedBeforePress is set in TaskTree.processMouseEvent before selection changes)
                    if (tree.pathWasSelectedBeforePress) {
                        tree.clearSelection()
                        return
                    }
                }
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    handleDoubleClick(e)
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })

        // Mouse motion listener for hand cursor on location links and hover highlight
        tree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val isOverLink = isMouseOverLocationLink(e)
                tree.cursor = if (isOverLink) {
                    java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                } else {
                    java.awt.Cursor.getDefaultCursor()
                }

                // Update hovered row for highlight
                val newHoveredRow = tree.getRowForLocation(e.x, e.y)
                if (renderer.hoveredRow != newHoveredRow) {
                    renderer.hoveredRow = newHoveredRow
                    tree.repaint()
                }
            }
        })

        // Clear hover when mouse exits
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (renderer.hoveredRow != -1) {
                    renderer.hoveredRow = -1
                    tree.repaint()
                }
            }
        })
    }

    private fun handleLocationClick(e: MouseEvent): Boolean {
        val path = tree.getPathForRowAt(e.x, e.y) ?: return false
        val node = path.lastPathComponent as? CheckedTreeNode ?: return false
        val task = node.userObject as? Task ?: return false

        if (!task.hasCodeLocation()) return false

        val row = tree.getRowForPath(path)
        val rowBounds = tree.getRowBounds(row) ?: return false

        // Configure renderer for this cell to get correct bounds
        tree.cellRenderer.getTreeCellRendererComponent(
            tree, node, tree.isRowSelected(row), tree.isExpanded(row),
            tree.model.isLeaf(node), row, tree.hasFocus()
        )

        if (renderer.linkText.isEmpty()) return false

        // Calculate click position relative to text start
        val textStartX = rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH + 4
        val clickRelativeX = e.x - textStartX

        // Use actual string width for accurate positioning
        val fm = tree.getFontMetrics(tree.font)
        val locationStartX = fm.stringWidth(renderer.textBeforeLink)
        val locationEndX = locationStartX + fm.stringWidth(renderer.linkText)

        if (clickRelativeX >= locationStartX && clickRelativeX <= locationEndX) {
            task.codeLocation?.let { location ->
                CodeLocationUtil.navigateToLocation(project, location)
            }
            return true
        }

        return false
    }

    private fun isMouseOverLocationLink(e: MouseEvent): Boolean {
        val path = tree.getPathForRowAt(e.x, e.y) ?: return false
        val node = path.lastPathComponent as? CheckedTreeNode ?: return false
        val task = node.userObject as? Task ?: return false

        if (!task.hasCodeLocation()) return false

        val row = tree.getRowForPath(path)
        val rowBounds = tree.getRowBounds(row) ?: return false

        tree.cellRenderer.getTreeCellRendererComponent(
            tree, node, tree.isRowSelected(row), tree.isExpanded(row),
            tree.model.isLeaf(node), row, tree.hasFocus()
        )

        if (renderer.linkText.isEmpty()) return false

        val textStartX = rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH + 4
        val clickRelativeX = e.x - textStartX

        val fm = tree.getFontMetrics(tree.font)
        val locationStartX = fm.stringWidth(renderer.textBeforeLink)
        val locationEndX = locationStartX + fm.stringWidth(renderer.linkText)

        return clickRelativeX >= locationStartX && clickRelativeX <= locationEndX
    }

    private fun handleDoubleClick(e: MouseEvent) {
        val path = tree.getPathForRowAt(e.x, e.y) ?: return
        val node = path.lastPathComponent as? CheckedTreeNode ?: return
        val task = node.userObject as? Task ?: return

        val row = tree.getRowForPath(path)
        val rowBounds = tree.getRowBounds(row) ?: return

        // Ignore clicks on expand/collapse arrow (left of rowBounds.x) or checkbox
        if (e.x < rowBounds.x) return
        val clickedOnCheckbox = e.x <= rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH

        if (!clickedOnCheckbox) {
            editTask(task)
        }
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return

        val path = tree.getPathForRowAt(e.x, e.y) ?: return
        val node = path.lastPathComponent as? CheckedTreeNode ?: return
        val task = node.userObject as? Task ?: return

        // If clicked task is not in current selection, select only that task
        // Otherwise keep multi-selection for copy operation
        if (!tree.isPathSelected(path)) {
            tree.selectionPath = path
        }
        val allSelectedTasks = getSelectedTasks()
        contextMenuBuilder.buildContextMenu(task, allSelectedTasks).show(tree, e.x, e.y)
    }

    private fun setupKeyboardShortcuts(tree: JTree) {
        val undoAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                taskService.undo()
            }
        }
        val redoAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                taskService.redo()
            }
        }
        val copyAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val tasks = getSelectedTasks()
                if (tasks.isEmpty()) return
                val text = tasks.joinToString("\n") { it.text }
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        }
        tree.getInputMap(JComponent.WHEN_FOCUSED).apply {
            // Undo: Ctrl+Z (Windows/Linux) or Cmd+Z (macOS)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "undoTask")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK), "undoTask")
            // Redo: Ctrl+Shift+Z (Windows/Linux) or Cmd+Shift+Z (macOS)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK), "redoTask")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK), "redoTask")
            // Also Ctrl+Y for redo on Windows/Linux
            put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), "redoTask")
            // Copy
            put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), "copyTaskText")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK), "copyTaskText")
        }
        tree.actionMap.apply {
            put("undoTask", undoAction)
            put("redoTask", redoAction)
            put("copyTaskText", copyAction)
        }
    }

    private fun setupExpansionListener(tree: JTree) {
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                if (!treeManager.isRefreshing()) {
                    treeManager.saveExpandedState()
                }
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                if (!treeManager.isRefreshing()) {
                    treeManager.saveExpandedState()
                }
            }
        })
    }

    // ============ ChecklistActionCallback Implementation ============

    override fun getSelectedTask(): Task? {
        val node = tree.lastSelectedPathComponent as? CheckedTreeNode
        return node?.userObject as? Task
    }

    private fun getSelectedTasks(): List<Task> {
        return tree.selectionPaths?.mapNotNull { path ->
            (path.lastPathComponent as? CheckedTreeNode)?.userObject as? Task
        } ?: emptyList()
    }

    private fun addSubtaskToTask(task: Task) {
        treeManager.selectTaskById(task.id)
        addSubtask()
    }

    override fun addSubtask() {
        val selectedTask = getSelectedTask() ?: return

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
            val location = dialog.getCodeLocation()
            if (text.isNotBlank()) {
                treeManager.ensureTaskExpanded(selectedTask.id)
                val subtask = taskService.addSubtask(selectedTask.id, text, priority)
                if (subtask != null) {
                    location?.let { taskService.setTaskLocation(subtask.id, it) }
                    treeManager.selectTaskById(subtask.id)
                }
            }
        }
    }

    override fun canMoveSelectedTask(direction: Int): Boolean {
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

    override fun moveSelectedTask(direction: Int) {
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
        treeManager.selectTaskById(taskIdToSelect)
    }

    override fun expandAll() {
        treeManager.expandAll()
        treeManager.saveExpandedState()
    }

    override fun collapseAll() {
        treeManager.collapseAll()
        treeManager.saveExpandedState()
    }

    override fun hasCompletedTasks(): Boolean {
        return taskService.getTasks().any { hasCompletedTasksRecursive(it) }
    }

    private fun hasCompletedTasksRecursive(task: Task): Boolean {
        if (task.isCompleted) return true
        return task.subtasks.any { hasCompletedTasksRecursive(it) }
    }

    override fun clearCompletedTasks() {
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

    override fun isHideCompletedEnabled(): Boolean {
        return taskService.isHideCompleted()
    }

    override fun toggleHideCompleted() {
        taskService.setHideCompleted(!taskService.isHideCompleted())
    }

    // ============ Task Operations ============

    private fun addTask() {
        val selectedTask = getSelectedTask()

        if (selectedTask != null) {
            if (selectedTask.canAddSubtask()) {
                // Add as subtask - keep parent selected for quick multi-add
                treeManager.ensureTaskExpanded(selectedTask.id)
                val dialog = NewTaskDialog(project, "New Subtask")
                if (dialog.showAndGet()) {
                    val text = dialog.getTaskText()
                    val priority = dialog.getSelectedPriority()
                    val location = dialog.getCodeLocation()
                    if (text.isNotBlank()) {
                        val subtask = taskService.addSubtask(selectedTask.id, text, priority)
                        if (subtask != null) {
                            location?.let { taskService.setTaskLocation(subtask.id, it) }
                            SwingUtilities.invokeLater {
                                treeManager.selectTaskById(selectedTask.id)
                                treeManager.scrollToTaskById(subtask.id)
                            }
                        }
                    }
                }
                return
            } else {
                // Max nesting reached - warn user and offer to add as root
                val result = Messages.showYesNoDialog(
                    project,
                    "Maximum nesting level (3) reached. Add as a new root task instead?",
                    "Cannot Add Subtask",
                    Messages.getQuestionIcon()
                )
                if (result != Messages.YES) {
                    return
                }
                // Fall through to add as root task
            }
        }

        // Add as root task - don't select it (allows quick multi-add)
        val dialog = NewTaskDialog(project)
        if (dialog.showAndGet()) {
            val text = dialog.getTaskText()
            val priority = dialog.getSelectedPriority()
            val location = dialog.getCodeLocation()
            if (text.isNotBlank()) {
                val task = taskService.addTask(text, priority)
                location?.let { taskService.setTaskLocation(task.id, it) }
                // Scroll to newly added task without selecting it
                SwingUtilities.invokeLater {
                    treeManager.scrollToTaskById(task.id)
                }
            }
        }
    }

    private fun removeSelectedTask() {
        val selectedTasks = getSelectedTasks()
        if (selectedTasks.isEmpty()) return

        // Stop focus timer for all deleted tasks
        selectedTasks.forEach { focusService.onTaskDeleted(it.id) }

        if (selectedTasks.size == 1) {
            taskService.removeTask(selectedTasks.first().id)
        } else {
            taskService.removeTasks(selectedTasks.map { it.id })
        }
    }

    private fun editSelectedTask() {
        val selectedTask = getSelectedTask() ?: return
        editTask(selectedTask)
    }

    private fun editTask(task: Task) {
        val dialog = NewTaskDialog(
            project,
            dialogTitle = "Edit Task",
            initialText = task.text,
            initialPriority = task.getPriorityEnum(),
            initialLocation = task.codeLocation
        )
        if (dialog.showAndGet()) {
            val newText = dialog.getTaskText()
            val newPriority = dialog.getSelectedPriority()
            val newLocation = dialog.getCodeLocation()
            if (newText.isNotBlank()) {
                if (newText != task.text) {
                    taskService.updateTaskText(task.id, newText)
                }
                if (newPriority != task.getPriorityEnum()) {
                    taskService.setTaskPriority(task.id, newPriority)
                }
                // Update location (handles add, update, and remove)
                if (newLocation != task.codeLocation) {
                    taskService.setTaskLocation(task.id, newLocation)
                }
            }
        }
    }

    // ============ Lifecycle ============

    private fun setupListeners() {
        taskListener = { SwingUtilities.invokeLater { treeManager.refreshTree() } }
        taskService.addListener(taskListener!!)

        focusListener = object : FocusService.FocusChangeListener {
            override fun onFocusChanged(focusedTaskId: String?) {
                tree.repaint(tree.visibleRect)
            }

            override fun onTimerTick() {
                tree.repaint(tree.visibleRect)
            }
        }
        focusService.addListener(focusListener!!)
    }

    override fun dispose() {
        instances.remove(project)
        taskListener?.let { taskService.removeListener(it) }
        taskListener = null
        focusListener?.let { focusService.removeListener(it) }
        focusListener = null
        animationService.dispose()
        focusBarPanel.dispose()
    }

    fun getContent(): JPanel = mainPanel

    /**
     * Selects a task by ID and scrolls to make it visible.
     * Used for navigation from gutter icons.
     */
    fun selectTaskById(taskId: String) {
        treeManager.selectTaskById(taskId)
    }
}
