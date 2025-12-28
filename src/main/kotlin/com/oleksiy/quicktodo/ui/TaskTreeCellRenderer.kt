package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.FocusService
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.JTree

class TaskTreeCellRenderer(
    private val focusService: FocusService
) : CheckboxTree.CheckboxTreeCellRenderer() {

    // Track location link text for click detection
    var textBeforeLink: String = ""
        private set
    var linkText: String = ""
        private set

    // Track hovered row for highlight
    var hoveredRow: Int = -1

    override fun customizeRenderer(
        tree: JTree?,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? CheckedTreeNode ?: return
        val task = node.userObject as? Task ?: run {
            textRenderer.append(node.userObject?.toString() ?: "Tasks")
            return
        }

        // Apply hover highlight (subtle background when not selected)
        if (!selected && row == hoveredRow) {
            val hoverColor = JBUI.CurrentTheme.List.Hover.background(true)
            textRenderer.background = hoverColor
        }

        val isFocused = focusService.isFocused(task.id)
        val isAncestorOfFocused = isAncestorOfFocusedTask(task)
        val hasAccumulatedTime = focusService.hasAccumulatedTime(task.id)

        // Check if effectively completed:
        // - For parents: only when ALL children are effectively completed
        // - For leaves: when the task itself is completed
        val isEffectivelyCompleted = if (node.childCount > 0) {
            isAllChildrenChecked(node)
        } else {
            node.isChecked
        }

        // Sync checkbox visual with effective completion state
        checkbox.isSelected = isEffectivelyCompleted

        val textAttributes = when {
            isEffectivelyCompleted -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_STRIKEOUT,
                SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            )
            isFocused || isAncestorOfFocused -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_UNDERLINE,
                null
            )
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        textRenderer.append(task.text, textAttributes)

        // Show completion progress for parent tasks (e.g., "2/5")
        if (task.subtasks.isNotEmpty()) {
            val (completed, total) = countCompletionProgress(task)
            textRenderer.append("  ")
            textRenderer.append(
                "$completed/$total",
                SimpleTextAttributes.GRAYED_ATTRIBUTES
            )
        }

        // Reset link tracking
        textBeforeLink = ""
        linkText = ""

        // Show timer if has accumulated time
        if (hasAccumulatedTime) {
            val timeStr = focusService.getFormattedTime(task.id)
            textRenderer.append("  ")
            textRenderer.append(
                "\u23F1 $timeStr",
                SimpleTextAttributes.GRAYED_ATTRIBUTES
            )
        }

        // Set flag icon based on priority
        val priority = task.getPriorityEnum()
        if (priority != Priority.NONE) {
            val icon = QuickTodoIcons.getIconForPriority(priority)
            if (icon != null) {
                textRenderer.icon = icon
                textRenderer.isIconOnTheRight = true
            }
        }

        // Show linked file location at the end, right-aligned with padding
        if (task.hasCodeLocation()) {
            // Add padding to push link towards the right
            textRenderer.append("        ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            textBeforeLink = textRenderer.toString()
            linkText = task.codeLocation!!.toDisplayString()
            val linkAttributes = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_UNDERLINE,
                SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            )
            textRenderer.append(linkText, linkAttributes)
        }
    }

    private fun countCompletionProgress(task: Task): Pair<Int, Int> {
        var completed = 0
        var total = 0
        for (subtask in task.subtasks) {
            total++
            if (subtask.isCompleted) {
                completed++
            }
            // Count nested subtasks too
            val (nestedCompleted, nestedTotal) = countCompletionProgress(subtask)
            completed += nestedCompleted
            total += nestedTotal
        }
        return Pair(completed, total)
    }

    private fun isAllChildrenChecked(node: CheckedTreeNode): Boolean {
        if (node.childCount == 0) return false
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: return false
            if (!child.isChecked && !isAllChildrenChecked(child)) {
                return false
            }
        }
        return true
    }

    private fun isAncestorOfFocusedTask(task: Task): Boolean {
        val focusedTaskId = focusService.getFocusedTaskId() ?: return false
        // Check if focused task is in this task's subtree (but not the task itself)
        return task.id != focusedTaskId && task.findTask(focusedTaskId) != null
    }
}
