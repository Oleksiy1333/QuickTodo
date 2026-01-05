package com.oleksiy.quicktodo.ui.util

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.ui.ChecklistConstants
import com.oleksiy.quicktodo.ui.TaskTree
import com.oleksiy.quicktodo.ui.TaskTreeCellRenderer
import com.intellij.ui.CheckedTreeNode
import java.awt.event.MouseEvent

/**
 * Utility for detecting description indicator interactions in the task tree.
 */
object DescriptionIndicatorDetector {

    /**
     * Data class holding the result of indicator bounds calculation.
     */
    data class IndicatorBounds(
        val startX: Int,
        val endX: Int,
        val task: Task
    )

    /**
     * Calculates the indicator bounds at the given mouse position.
     * Returns null if there's no description indicator at that position.
     */
    fun getIndicatorBoundsAt(
        tree: TaskTree,
        renderer: TaskTreeCellRenderer,
        event: MouseEvent
    ): IndicatorBounds? {
        val path = tree.getPathForRowAt(event.x, event.y) ?: return null
        val node = path.lastPathComponent as? CheckedTreeNode ?: return null
        val task = node.userObject as? Task ?: return null

        if (!task.hasDescription()) return null

        val row = tree.getRowForPath(path)
        val rowBounds = tree.getRowBounds(row) ?: return null

        // Configure renderer for this cell to get correct bounds
        tree.cellRenderer.getTreeCellRendererComponent(
            tree, node, tree.isRowSelected(row), tree.isExpanded(row),
            tree.model.isLeaf(node), row, tree.hasFocus()
        )

        if (renderer.descriptionIndicatorText.isEmpty()) return null

        // Calculate text start position (after checkbox and icon if present)
        val textStartX = rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH + 4 + renderer.iconWidth

        // Use font metrics for accurate positioning
        val fm = tree.getFontMetrics(tree.font)
        val indicatorStartX = textStartX + fm.stringWidth(renderer.textBeforeDescriptionIndicator)
        val indicatorEndX = indicatorStartX + fm.stringWidth(renderer.descriptionIndicatorText)

        return IndicatorBounds(indicatorStartX, indicatorEndX, task)
    }

    /**
     * Checks if the mouse is currently over a description indicator.
     */
    fun isMouseOverIndicator(
        tree: TaskTree,
        renderer: TaskTreeCellRenderer,
        event: MouseEvent
    ): Boolean {
        val bounds = getIndicatorBoundsAt(tree, renderer, event) ?: return false
        return event.x >= bounds.startX && event.x <= bounds.endX
    }

    /**
     * Returns the task if the click was on a description indicator, null otherwise.
     */
    fun getClickedTask(
        tree: TaskTree,
        renderer: TaskTreeCellRenderer,
        event: MouseEvent
    ): Task? {
        val bounds = getIndicatorBoundsAt(tree, renderer, event) ?: return null
        if (event.x >= bounds.startX && event.x <= bounds.endX) {
            return bounds.task
        }
        return null
    }
}
