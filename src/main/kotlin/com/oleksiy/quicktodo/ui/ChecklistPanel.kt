package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.action.AddSubtaskAction
import com.oleksiy.quicktodo.action.ChecklistActionCallback
import com.oleksiy.quicktodo.action.ClearCompletedAction
import com.oleksiy.quicktodo.action.CollapseAllAction
import com.oleksiy.quicktodo.action.ExpandAllAction
import com.oleksiy.quicktodo.action.MoveTaskAction
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.ui.dnd.TaskDragDropHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
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

/**
 * Main panel for the QuickTodo checklist tool window.
 * Implements ChecklistActionCallback for toolbar actions and Disposable for cleanup.
 */
class ChecklistPanel(private val project: Project) : ChecklistActionCallback, Disposable {

    private val taskService = TaskService.getInstance(project)
    private lateinit var tree: CheckboxTree
    private lateinit var treeManager: TaskTreeManager
    private lateinit var dragDropHandler: TaskDragDropHandler
    private lateinit var contextMenuBuilder: TaskContextMenuBuilder
    private val mainPanel = JPanel(BorderLayout())
    private var taskListener: (() -> Unit)? = null

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
        contextMenuBuilder = TaskContextMenuBuilder(taskService) { task -> editTask(task) }

        dragDropHandler.setup()
        treeManager.refreshTree()

        val toolbarDecorator = createToolbarDecorator()
        val decoratorPanel = toolbarDecorator.createPanel()
        setupRightToolbar(decoratorPanel)

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
            ExpandAllAction(this),
            CollapseAllAction(this),
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
        val renderer = TaskTreeCellRenderer()
        val policy = CheckboxTreeBase.CheckPolicy(false, false, false, false)

        val checkboxTree = object : CheckboxTree(renderer, CheckedTreeNode("Tasks"), policy) {
            override fun onNodeStateChanged(node: CheckedTreeNode) {
                val task = node.userObject as? Task ?: return
                taskService.setTaskCompletion(task.id, node.isChecked)
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
                if (e.clickCount == 2 && !e.isPopupTrigger) {
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

        tree.selectionPath = path
        contextMenuBuilder.buildContextMenu(task).show(tree, e.x, e.y)
    }

    private fun setupKeyboardShortcuts(tree: CheckboxTree) {
        val undoAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                taskService.undoRemoveTask()
            }
        }
        tree.getInputMap(JComponent.WHEN_FOCUSED).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "undoRemoveTask")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK), "undoRemoveTask")
        }
        tree.actionMap.put("undoRemoveTask", undoAction)
    }

    private fun setupExpansionListener(tree: CheckboxTree) {
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) =
                treeManager.saveExpandedState()

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) =
                treeManager.saveExpandedState()
        })
    }

    // ============ ChecklistActionCallback Implementation ============

    override fun getSelectedTask(): Task? {
        val node = tree.lastSelectedPathComponent as? CheckedTreeNode
        return node?.userObject as? Task
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
        taskService.removeTask(selectedTask.id)
    }

    private fun editSelectedTask() {
        val selectedTask = getSelectedTask() ?: return
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

    // ============ Lifecycle ============

    private fun setupListeners() {
        taskListener = { treeManager.refreshTree() }
        taskService.addListener(taskListener!!)
    }

    override fun dispose() {
        taskListener?.let { taskService.removeListener(it) }
        taskListener = null
    }

    fun getContent(): JPanel = mainPanel
}
