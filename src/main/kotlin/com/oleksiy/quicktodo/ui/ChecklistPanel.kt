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
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DropMode
import javax.swing.JPanel
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
                if (!::mouseHandler.isInitialized) return null
                val path = getPathForRowAt(event.x, event.y) ?: return null
                val node = path.lastPathComponent as? CheckedTreeNode ?: return null
                val task = node.userObject as? Task ?: return null

                if (task.hasCodeLocation() && mouseHandler.isMouseOverLocationLink(event)) {
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
}
