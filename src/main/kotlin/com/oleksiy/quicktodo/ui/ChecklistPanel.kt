package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.action.ChecklistActionCallback
import com.oleksiy.quicktodo.action.ClearCompletedAction
import com.oleksiy.quicktodo.action.MoveTaskAction
import com.oleksiy.quicktodo.action.RedoAction
import com.oleksiy.quicktodo.action.StartAutomationAction
import com.oleksiy.quicktodo.action.UndoAction
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.AutomationService
import com.oleksiy.quicktodo.service.FocusService
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.settings.QuickTodoSettings
import com.oleksiy.quicktodo.settings.TooltipBehavior
import com.oleksiy.quicktodo.ui.dnd.TaskDragDropHandler
import com.oleksiy.quicktodo.ui.handler.AddTaskHandler
import com.oleksiy.quicktodo.ui.handler.ChecklistKeyboardHandler
import com.oleksiy.quicktodo.ui.handler.ChecklistMouseHandler
import com.oleksiy.quicktodo.ui.handler.EditTaskHandler
import com.oleksiy.quicktodo.ui.handler.RemoveTaskHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import com.intellij.openapi.ide.CopyPasteManager
import javax.swing.JTree
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
    private val automationService = AutomationService.getInstance(project)
    private lateinit var tree: TaskTree
    private lateinit var renderer: TaskTreeCellRenderer
    private lateinit var treeManager: TaskTreeManager
    private lateinit var dragDropHandler: TaskDragDropHandler
    private lateinit var contextMenuBuilder: TaskContextMenuBuilder
    private lateinit var mouseHandler: ChecklistMouseHandler
    private lateinit var keyboardHandler: ChecklistKeyboardHandler
    private lateinit var addTaskHandler: AddTaskHandler
    private lateinit var editTaskHandler: EditTaskHandler
    private lateinit var removeTaskHandler: RemoveTaskHandler
    private lateinit var focusBarPanel: FocusBarPanel
    private lateinit var automationBarPanel: AutomationBarPanel
    private lateinit var dailyStatsPanel: DailyStatsPanel
    private val mainPanel = JPanel(BorderLayout())
    private var taskListener: (() -> Unit)? = null
    private var focusListener: FocusService.FocusChangeListener? = null
    private val animationService = CheckmarkAnimationService()
    private val tooltipPopup = TaskTooltipPopup(project, focusService)

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
            onEditTask = { task -> editTaskHandler.editTask(task) },
            onAddSubtask = { task -> addTaskHandler.addSubtaskToTask(task) }
        )
        mouseHandler = ChecklistMouseHandler(
            project,
            tree,
            renderer,
            contextMenuBuilder,
            onEditTask = { task -> editTaskHandler.editTask(task) },
            getSelectedTasks = { getSelectedTasks() }
        )
        addTaskHandler = AddTaskHandler(
            project,
            taskService,
            treeManager,
            getSelectedTask = { getSelectedTask() }
        )
        keyboardHandler = ChecklistKeyboardHandler(
            tree,
            onUndo = { taskService.undo() },
            onRedo = { taskService.redo() },
            onAddTask = { addTaskHandler.addTask() },
            getSelectedTasks = { getSelectedTasks() },
            onMoveUp = { moveSelectedTask(-1) },
            onMoveDown = { moveSelectedTask(1) },
            canMoveUp = { canMoveSelectedTask(-1) },
            canMoveDown = { canMoveSelectedTask(1) }
        )
        editTaskHandler = EditTaskHandler(
            project,
            taskService,
            getSelectedTask = { getSelectedTask() }
        )
        removeTaskHandler = RemoveTaskHandler(
            taskService,
            focusService,
            getSelectedTasks = { getSelectedTasks() }
        )

        dragDropHandler.setup()
        mouseHandler.setup()
        keyboardHandler.setup()
        treeManager.refreshTree()

        val toolbarDecorator = createToolbarDecorator()
        val decoratorPanel = toolbarDecorator.createPanel()
        setupRightToolbar(decoratorPanel)

        dailyStatsPanel = DailyStatsPanel(project)
        focusBarPanel = FocusBarPanel(project)
        automationBarPanel = AutomationBarPanel(project)

        // Add stats panel inside decorator for seamless integration
        decoratorPanel.add(dailyStatsPanel, BorderLayout.SOUTH)

        // Create a north panel to hold both focus and automation bars
        val northPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(focusBarPanel)
            add(automationBarPanel)
        }

        mainPanel.add(northPanel, BorderLayout.NORTH)
        mainPanel.add(decoratorPanel, BorderLayout.CENTER)
        mainPanel.border = JBUI.Borders.empty()
    }

    private fun createToolbarDecorator(): ToolbarDecorator {
        val moveUpAction = MoveTaskAction(-1, this).apply { registerShortcut(tree) }
        val moveDownAction = MoveTaskAction(1, this).apply { registerShortcut(tree) }

        return ToolbarDecorator.createDecorator(tree)
            .setAddAction { addTaskHandler.addTask() }
            .setRemoveAction { removeTaskHandler.removeSelectedTasks() }
            .setEditAction { editTaskHandler.editSelectedTask() }
            .setEditActionUpdater { getSelectedTask() != null }
            .addExtraActions(
                moveUpAction,
                moveDownAction
            )
            .setPanelBorder(JBUI.Borders.empty())
            .setScrollPaneBorder(JBUI.Borders.empty())
            .setToolbarBorder(JBUI.Borders.empty())
    }

    private fun setupRightToolbar(decoratorPanel: JPanel) {
        // Center toolbar with Claude button
        val centerActionGroup = DefaultActionGroup(
            StartAutomationAction(
                automationServiceProvider = { automationService },
                projectProvider = { project }
            )
        )
        val centerToolbar = ActionManager.getInstance()
            .createActionToolbar("ChecklistCenterToolbar", centerActionGroup, true)
        centerToolbar.targetComponent = tree

        // Right toolbar with other actions
        val rightActionGroup = DefaultActionGroup(
            UndoAction { taskService },
            RedoAction { taskService },
            ClearCompletedAction(this)
        )
        val rightToolbar = ActionManager.getInstance()
            .createActionToolbar("ChecklistRightToolbar", rightActionGroup, true)
        rightToolbar.targetComponent = tree

        val decoratorLayout = decoratorPanel.layout as? BorderLayout ?: return
        val originalToolbar = decoratorLayout.getLayoutComponent(BorderLayout.NORTH) ?: return

        // Remove border from original toolbar
        (originalToolbar as? JPanel)?.border = JBUI.Borders.empty()

        decoratorPanel.remove(originalToolbar)
        val toolbarRowPanel = JPanel(BorderLayout()).apply {
            add(originalToolbar, BorderLayout.WEST)
            add(centerToolbar.component, BorderLayout.CENTER)
            add(rightToolbar.component, BorderLayout.EAST)
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
                if (!::renderer.isInitialized) return null
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

        // Enable tooltips for code location links
        javax.swing.ToolTipManager.sharedInstance().registerComponent(taskTree)

        setupMouseListeners(taskTree)
        setupKeyboardShortcuts(taskTree)
        setupExpansionListener(taskTree)

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
        // Mouse motion listener for hand cursor on location links and hover highlight
        var tooltipTimer: javax.swing.Timer? = null
        var lastHoveredTask: Task? = null

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // Hide tooltip immediately when clicking on a task
                tooltipPopup.hideTooltip()
                // Cancel any pending tooltip timer to prevent it from showing
                tooltipTimer?.stop()
            }
        })

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

                // Show tooltip after delay
                val path = tree.getPathForLocation(e.x, e.y)
                val task = (path?.lastPathComponent as? CheckedTreeNode)?.userObject as? Task

                if (task != lastHoveredTask) {
                    tooltipTimer?.stop()

                    if (task == null) {
                        // Mouse moved away from tasks - schedule hide with delay
                        tooltipPopup.scheduleHide()
                    } else {
                        // Different task - schedule hide with delay, then show new tooltip after 800ms
                        tooltipPopup.scheduleHide()

                        // Check if tooltip should be shown based on settings
                        if (shouldShowTooltip(task, path)) {
                            tooltipTimer = javax.swing.Timer(800) {
                                val mouseLocation = com.intellij.ui.awt.RelativePoint(e)
                                tooltipPopup.showTooltip(task, mouseLocation)
                            }.apply {
                                isRepeats = false
                                start()
                            }
                        }
                    }

                    lastHoveredTask = task
                }
            }
        })

        // Clear hover when mouse exits tree
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (renderer.hoveredRow != -1) {
                    renderer.hoveredRow = -1
                    tree.repaint()
                }
                tooltipTimer?.stop()
                tooltipTimer = null
                lastHoveredTask = null
                // Schedule hide with delay to allow moving mouse to popup
                tooltipPopup.scheduleHide()
            }
        })
    }

    private fun handleLocationClick(e: MouseEvent): Boolean {
        val path = tree.getPathForLocation(e.x, e.y) ?: return false
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
        val path = tree.getPathForLocation(e.x, e.y) ?: return false
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
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? CheckedTreeNode ?: return
        val task = node.userObject as? Task ?: return

        val row = tree.getRowForPath(path)
        val rowBounds = tree.getRowBounds(row) ?: return
        val clickedOnCheckbox = e.x in rowBounds.x..(rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH)

        if (!clickedOnCheckbox) {
            editTaskHandler.editTask(task)
        }
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = tree.getPathForLocation(e.x, e.y) ?: return
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

    override fun addSubtask() {
        addTaskHandler.addSubtask()
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

        // When moving down, add 1 to target index to compensate for index adjustment
        // in TaskRepository (it subtracts 1 when the source is before the target)
        val targetIndex = if (direction > 0) newIndex + 1 else newIndex

        val taskIdToSelect = selectedTask.id
        taskService.moveTask(selectedTask.id, parentTask?.id, targetIndex)
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
        dailyStatsPanel.dispose()
        focusBarPanel.dispose()
        automationBarPanel.dispose()
    }

    fun getContent(): JPanel = mainPanel

    /**
     * Selects a task by ID and scrolls to make it visible.
     * Used for navigation from gutter icons.
     */
    fun selectTaskById(taskId: String) {
        treeManager.selectTaskById(taskId)
    }

    /**
     * Determines if a tooltip should be shown based on settings and truncation state.
     */
    private fun shouldShowTooltip(task: Task, path: javax.swing.tree.TreePath?): Boolean {
        val settings = QuickTodoSettings.getInstance()

        return when (settings.getTooltipBehavior()) {
            TooltipBehavior.ALWAYS -> true
            TooltipBehavior.NEVER -> false
            TooltipBehavior.TRUNCATED -> isTaskTruncated(task, path)
        }
    }

    /**
     * Checks if a task's text is truncated in the tree view.
     */
    private fun isTaskTruncated(task: Task, path: javax.swing.tree.TreePath?): Boolean {
        if (path == null) return false

        val row = tree.getRowForPath(path)
        if (row < 0) return false

        val rowBounds = tree.getRowBounds(row) ?: return false

        // Get the renderer component to measure the actual required width
        val node = path.lastPathComponent as? CheckedTreeNode ?: return false
        val component = tree.cellRenderer.getTreeCellRendererComponent(
            tree, node, false, tree.isExpanded(path), node.isLeaf, row, false
        )

        val preferredWidth = component.preferredSize.width

        // Get the actual visible area for this row (accounting for tree viewport)
        val visibleRect = tree.visibleRect
        val treeX = rowBounds.x
        val treeRightEdge = visibleRect.x + visibleRect.width

        // Available width is from the row start to the right edge of visible area
        val availableWidth = (treeRightEdge - treeX).coerceAtLeast(0)

        // Consider it truncated if content extends beyond the visible area
        return preferredWidth > availableWidth
    }
}
