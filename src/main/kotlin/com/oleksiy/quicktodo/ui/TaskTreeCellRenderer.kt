package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree

class TaskTreeCellRenderer : CheckboxTree.CheckboxTreeCellRenderer() {

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

        // Check if effectively completed: either directly checked or all children are checked
        val isEffectivelyCompleted = node.isChecked || isAllChildrenChecked(node)
        val attributes = if (isEffectivelyCompleted) {
            SimpleTextAttributes(
                SimpleTextAttributes.STYLE_STRIKEOUT,
                SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
            )
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        textRenderer.append(task.text, attributes)

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
}
