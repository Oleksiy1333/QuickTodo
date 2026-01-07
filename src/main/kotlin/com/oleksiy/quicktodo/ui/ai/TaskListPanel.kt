package com.oleksiy.quicktodo.ui.ai

import com.intellij.icons.AllIcons
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.ui.QuickTodoIcons
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Left panel showing grouped task list.
 * Parent tasks appear as collapsible section headers.
 * Subtasks appear indented below their parent.
 * Standalone tasks (no subtasks) appear as regular items.
 */
class TaskListPanel : JPanel(BorderLayout()) {

    private val selectedTaskIds = mutableSetOf<String>()
    private val collapsedSections = mutableSetOf<String>()
    private var focusedTaskId: String? = null

    private var onTaskFocused: ((Task) -> Unit)? = null
    private var onSelectionChanged: (() -> Unit)? = null

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private var allTasks: List<Task> = emptyList()
    private val taskMap = mutableMapOf<String, Task>() // id -> task for quick lookup

    init {
        isOpaque = false

        // Header with Select All / Unselect All links
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 4, 8)

            val selectAllLink = HyperlinkLabel("Select All").apply {
                addHyperlinkListener { selectAll() }
            }
            add(selectAllLink)

            add(Box.createHorizontalStrut(JBUI.scale(12)))

            val unselectAllLink = HyperlinkLabel("Unselect All").apply {
                addHyperlinkListener { unselectAll() }
            }
            add(unselectAllLink)
        }
        add(headerPanel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    fun setTasks(tasks: List<Task>) {
        allTasks = tasks
        taskMap.clear()
        buildTaskMap(tasks)
        rebuildList()
    }

    private fun buildTaskMap(tasks: List<Task>) {
        for (task in tasks) {
            if (!task.isCompleted) {
                taskMap[task.id] = task
                buildTaskMap(task.subtasks)
            }
        }
    }

    private fun rebuildList() {
        contentPanel.removeAll()

        for (task in allTasks) {
            if (task.isCompleted) continue

            val hasSubtasks = task.subtasks.any { !it.isCompleted }

            if (hasSubtasks) {
                // Section header with collapsible subtasks
                addSectionHeader(task)

                if (!collapsedSections.contains(task.id)) {
                    for (subtask in task.subtasks) {
                        if (!subtask.isCompleted) {
                            addTaskRow(subtask, indentLevel = 1)
                        }
                    }
                }
            } else {
                // Standalone task (no subtasks)
                addTaskRow(task, indentLevel = 0)
            }
        }

        contentPanel.add(Box.createVerticalGlue())
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun addSectionHeader(task: Task) {
        val isCollapsed = collapsedSections.contains(task.id)
        val incompleteSubtasks = task.subtasks.count { !it.isCompleted }
        val isFocused = task.id == focusedTaskId

        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = isFocused
            if (isFocused) {
                background = JBColor.namedColor(
                    "List.selectionBackground",
                    JBColor(0xD4E2FF, 0x2D3548)
                )
            }
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            border = JBUI.Borders.empty(4, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        // Left side: collapse icon + checkbox + task name
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }

        // Collapse/expand icon
        val collapseIcon = JBLabel(
            if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        ).apply {
            border = JBUI.Borders.empty(0, 4)
        }
        leftPanel.add(collapseIcon)

        // Checkbox
        val checkbox = JBCheckBox().apply {
            this.isSelected = selectedTaskIds.contains(task.id)
            isOpaque = false
            addActionListener {
                toggleSelection(task, this.isSelected)
            }
        }
        leftPanel.add(checkbox)

        // Priority icon
        val priority = task.getPriorityEnum()
        if (priority != Priority.NONE) {
            val priorityLabel = JBLabel(QuickTodoIcons.getIconForPriority(priority))
            leftPanel.add(priorityLabel)
            leftPanel.add(Box.createHorizontalStrut(4))
        }

        // Task name
        val nameLabel = JBLabel(task.text).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
        }
        leftPanel.add(nameLabel)

        headerPanel.add(leftPanel, BorderLayout.WEST)

        // Right side: subtask count
        val countLabel = JBLabel("($incompleteSubtasks)").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            border = JBUI.Borders.empty(0, 8)
        }
        headerPanel.add(countLabel, BorderLayout.EAST)

        // Click handler for collapse/expand and focus
        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Check if click is on checkbox area
                val checkboxBounds = checkbox.bounds
                val clickInCheckbox = e.x >= leftPanel.x + checkboxBounds.x &&
                        e.x <= leftPanel.x + checkboxBounds.x + checkboxBounds.width

                if (clickInCheckbox) {
                    // Let checkbox handle it
                    return
                }

                // Check if click is on collapse icon area
                val iconBounds = collapseIcon.bounds
                val clickInIcon = e.x >= leftPanel.x + iconBounds.x &&
                        e.x <= leftPanel.x + iconBounds.x + iconBounds.width + 10

                if (clickInIcon) {
                    toggleCollapse(task.id)
                } else {
                    // Focus this task
                    setFocusedTask(task)
                }
            }
        })

        contentPanel.add(headerPanel)
    }

    private fun addTaskRow(task: Task, indentLevel: Int) {
        val isFocused = task.id == focusedTaskId

        val rowPanel = JPanel(BorderLayout()).apply {
            isOpaque = isFocused
            if (isFocused) {
                background = JBColor.namedColor(
                    "List.selectionBackground",
                    JBColor(0xD4E2FF, 0x2D3548)
                )
            }
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            val leftIndent = JBUI.scale(24 + indentLevel * 20)
            border = EmptyBorder(2, leftIndent, 2, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
        }

        // Checkbox
        val checkbox = JBCheckBox().apply {
            this.isSelected = selectedTaskIds.contains(task.id)
            isOpaque = false
            addActionListener {
                toggleSelection(task, this.isSelected)
            }
        }
        leftPanel.add(checkbox)

        // Priority icon
        val priority = task.getPriorityEnum()
        if (priority != Priority.NONE) {
            val priorityLabel = JBLabel(QuickTodoIcons.getIconForPriority(priority))
            leftPanel.add(priorityLabel)
            leftPanel.add(Box.createHorizontalStrut(4))
        }

        // Task name
        val nameLabel = JBLabel(task.text)
        leftPanel.add(nameLabel)

        rowPanel.add(leftPanel, BorderLayout.WEST)

        // Click handler
        rowPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Check if click is on checkbox area
                val checkboxBounds = checkbox.bounds
                val clickX = e.x - rowPanel.insets.left
                val clickInCheckbox = clickX >= checkboxBounds.x &&
                        clickX <= checkboxBounds.x + checkboxBounds.width

                if (!clickInCheckbox) {
                    // Focus this task
                    setFocusedTask(task)
                }
            }
        })

        // Add subtle hover effect (only if not focused)
        rowPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (task.id != focusedTaskId) {
                    rowPanel.background = JBColor.namedColor(
                        "List.hoverBackground",
                        JBColor(0xF5F5F5, 0x3C3F41)
                    )
                    rowPanel.isOpaque = true
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (task.id != focusedTaskId) {
                    rowPanel.isOpaque = false
                    rowPanel.repaint()
                }
            }
        })

        contentPanel.add(rowPanel)
    }

    private fun toggleSelection(task: Task, selected: Boolean) {
        if (selected) {
            selectedTaskIds.add(task.id)
            // Also select all subtasks
            selectSubtasks(task, true)
        } else {
            selectedTaskIds.remove(task.id)
            // Also deselect all subtasks
            selectSubtasks(task, false)
        }
        onSelectionChanged?.invoke()
        rebuildList()
    }

    private fun selectSubtasks(task: Task, selected: Boolean) {
        for (subtask in task.subtasks) {
            if (!subtask.isCompleted) {
                if (selected) {
                    selectedTaskIds.add(subtask.id)
                } else {
                    selectedTaskIds.remove(subtask.id)
                }
                selectSubtasks(subtask, selected)
            }
        }
    }

    private fun toggleCollapse(taskId: String) {
        if (collapsedSections.contains(taskId)) {
            collapsedSections.remove(taskId)
        } else {
            collapsedSections.add(taskId)
        }
        rebuildList()
    }

    private fun setFocusedTask(task: Task) {
        focusedTaskId = task.id
        rebuildList() // Refresh to show selection highlight
        onTaskFocused?.invoke(task)
    }

    /**
     * Returns selected task IDs, filtering out subtasks whose parent is also selected.
     * This prevents subtasks from being executed twice (once as part of parent's prompt,
     * once as standalone tasks).
     */
    fun getSelectedTaskIds(): List<String> {
        return selectedTaskIds.filter { taskId ->
            val task = taskMap[taskId] ?: return@filter true
            val parent = findParentTask(task)
            // Include if no parent, or parent is not selected
            parent == null || !selectedTaskIds.contains(parent.id)
        }.toList()
    }

    private fun findParentTask(task: Task): Task? {
        for (rootTask in allTasks) {
            if (rootTask.id == task.id) return null // It's a root task, no parent
            val parent = findParentRecursive(rootTask, task.id)
            if (parent != null) return parent
        }
        return null
    }

    private fun findParentRecursive(current: Task, targetId: String): Task? {
        for (subtask in current.subtasks) {
            if (subtask.id == targetId) return current
            val found = findParentRecursive(subtask, targetId)
            if (found != null) return found
        }
        return null
    }

    fun setOnTaskFocused(listener: (Task) -> Unit) {
        onTaskFocused = listener
    }

    fun setOnSelectionChanged(listener: () -> Unit) {
        onSelectionChanged = listener
    }

    fun findTask(taskId: String): Task? = taskMap[taskId]

    /**
     * Updates the mode for a task (used when bulk mode is applied).
     * This doesn't change the list display, just ensures selection is tracked.
     */
    fun ensureSelected(taskId: String) {
        selectedTaskIds.add(taskId)
    }

    /**
     * Selects all incomplete tasks.
     */
    fun selectAll() {
        selectedTaskIds.clear()
        selectedTaskIds.addAll(taskMap.keys)
        onSelectionChanged?.invoke()
        rebuildList()
    }

    /**
     * Deselects all tasks.
     */
    fun unselectAll() {
        selectedTaskIds.clear()
        onSelectionChanged?.invoke()
        rebuildList()
    }
}
