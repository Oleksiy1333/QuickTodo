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
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Main panel for the QuickTodo checklist tool window.
 * Implements ChecklistActionCallback for toolbar actions and Disposable for cleanup.
 */
class ChecklistPanel(private val project: Project) : ChecklistActionCallback, Disposable {

    private val taskService = TaskService.getInstance(project)
    private val focusService = FocusService.getInstance(project)
    private lateinit var tree: CheckboxTree
    private lateinit var treeManager: TaskTreeManager
    private lateinit var dragDropHandler: TaskDragDropHandler
    private lateinit var contextMenuBuilder: TaskContextMenuBuilder
    private lateinit var focusBarPanel: FocusBarPanel
    private val mainPanel = JPanel(BorderLayout())
    private var taskListener: (() -> Unit)? = null
    private var focusListener: FocusService.FocusChangeListener? = null

    init {
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        tree = createCheckboxTree()
        treeManager = TaskTreeManager(tree, taskService)
        dragDropHandler = TaskDragDropHandler(
            tree,
            taskService,
            onTaskMoved = { taskId -> treeManager.selectTaskById(taskId) },
            ensureTaskExpanded = { taskId -> treeManager.ensureTaskExpanded(taskId) }
        )
        contextMenuBuilder = TaskContextMenuBuilder(
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

    private fun createCheckboxTree(): CheckboxTree {
        val renderer = TaskTreeCellRenderer(focusService)
        val policy = CheckboxTreeBase.CheckPolicy(false, false, false, false)

        val checkboxTree = object : CheckboxTree(renderer, CheckedTreeNode("Tasks"), policy) {
            override fun onNodeStateChanged(node: CheckedTreeNode) {
                val task = node.userObject as? Task ?: return
                taskService.setTaskCompletion(task.id, node.isChecked)
                if (node.isChecked) {
                    focusService.onTaskCompleted(task.id)
                }
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (dragDropHandler.dropTargetRow >= 0 &&
                    dragDropHandler.dropPosition != DropPosition.NONE
                ) {
                    DropIndicatorPainter.paint(
                        g, this,
                        dragDropHandler.dropTargetRow,
                        dragDropHandler.dropPosition
                    )
                }
            }
        }

        setupMouseListeners(checkboxTree)
        setupKeyboardShortcuts(checkboxTree)
        setupExpansionListener(checkboxTree)

        checkboxTree.dragEnabled = true
        checkboxTree.dropMode = DropMode.ON_OR_INSERT

        return checkboxTree
    }

    private fun setupMouseListeners(tree: CheckboxTree) {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    handleDoubleClick(e, tree)
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e, tree)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e, tree)
        })
    }

    private fun handleDoubleClick(e: MouseEvent, tree: CheckboxTree) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? CheckedTreeNode ?: return
        val task = node.userObject as? Task ?: return

        val row = tree.getRowForPath(path)
        val rowBounds = tree.getRowBounds(row) ?: return
        val clickedOnCheckbox = e.x in rowBounds.x..(rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH)

        if (!clickedOnCheckbox) {
            editTask(task)
        }
    }

    private fun maybeShowContextMenu(e: MouseEvent, tree: CheckboxTree) {
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

    private fun setupKeyboardShortcuts(tree: CheckboxTree) {
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

    private fun setupExpansionListener(tree: CheckboxTree) {
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
            if (text.isNotBlank()) {
                treeManager.ensureTaskExpanded(selectedTask.id)
                val subtask = taskService.addSubtask(selectedTask.id, text, priority)
                if (subtask != null) {
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
        val dialog = NewTaskDialog(project)
        if (dialog.showAndGet()) {
            val text = dialog.getTaskText()
            val priority = dialog.getSelectedPriority()
            if (text.isNotBlank()) {
                val task = taskService.addTask(text, priority)
                treeManager.selectTaskById(task.id)
            }
        }
    }

    private fun removeSelectedTask() {
        val selectedTask = getSelectedTask() ?: return
        focusService.onTaskDeleted(selectedTask.id)
        taskService.removeTask(selectedTask.id)
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
            initialPriority = task.getPriorityEnum()
        )
        if (dialog.showAndGet()) {
            val newText = dialog.getTaskText()
            val newPriority = dialog.getSelectedPriority()
            if (newText.isNotBlank()) {
                if (newText != task.text) {
                    taskService.updateTaskText(task.id, newText)
                }
                if (newPriority != task.getPriorityEnum()) {
                    taskService.setTaskPriority(task.id, newPriority)
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
        taskListener?.let { taskService.removeListener(it) }
        taskListener = null
        focusListener?.let { focusService.removeListener(it) }
        focusListener = null
        focusBarPanel.dispose()
    }

    fun getContent(): JPanel = mainPanel
}
