package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.FocusService
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import javax.swing.JTree

class TaskTreeCellRenderer(
    private val focusService: FocusService
) : CheckboxTree.CheckboxTreeCellRenderer() {

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

        val isFocused = focusService.isFocused(task.id)
        val hasAccumulatedTime = focusService.hasAccumulatedTime(task.id)

        // Apply focus background highlighting
        if (isFocused && !selected) {
            textRenderer.background = FOCUS_BACKGROUND_COLOR
            textRenderer.isOpaque = true
        }

        // Check if effectively completed:
        // - For parents: only when ALL children are effectively completed
        // - For leaves: when the task itself is completed
        val isEffectivelyCompleted = if (node.childCount > 0) {
            isAllChildrenChecked(node)
        } else {
            node.isChecked
        }

        // Sync checkbox visual with effective completion state
        myCheckbox.isSelected = isEffectivelyCompleted

        val textAttributes = when {
            isEffectivelyCompleted -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_STRIKEOUT,
                SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            )
            isFocused -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                null
            )
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        textRenderer.append(task.text, textAttributes)

        // Show timer if has accumulated time
        if (hasAccumulatedTime) {
            val timeStr = focusService.getFormattedTime(task.id)
            textRenderer.append("  ")
            textRenderer.append(
                "\u23F1 $timeStr",
                SimpleTextAttributes.GRAYED_ATTRIBUTES
            )
        }

        // Set flag icon at the end based on priority
        val priority = task.getPriorityEnum()
        if (priority != Priority.NONE) {
            val icon = QuickTodoIcons.getIconForPriority(priority)
            if (icon != null) {
                textRenderer.icon = icon
                textRenderer.isIconOnTheRight = true
            }
        }
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

    companion object {
        private val FOCUS_BACKGROUND_COLOR = JBColor(
            Color(255, 243, 205),  // Light theme: warm yellow
            Color(60, 55, 40)      // Dark theme: muted gold
        )
    }
}
