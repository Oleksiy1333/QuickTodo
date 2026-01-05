package com.oleksiy.quicktodo.ui.handler

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.ui.ChecklistConstants
import com.oleksiy.quicktodo.ui.CodeLocationUtil
import com.oleksiy.quicktodo.ui.TaskContextMenuBuilder
import com.oleksiy.quicktodo.ui.TaskTree
import com.oleksiy.quicktodo.ui.TaskTreeCellRenderer
import com.oleksiy.quicktodo.ui.util.DescriptionIndicatorDetector
import com.oleksiy.quicktodo.ui.util.LocationLinkDetector
import com.intellij.openapi.project.Project
import com.intellij.ui.CheckedTreeNode
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter

/**
 * Handles all mouse interactions for the checklist tree.
 * Supports click, double-click, context menu, location link navigation, and hover effects.
 */
class ChecklistMouseHandler(
    private val project: Project,
    private val tree: TaskTree,
    private val renderer: TaskTreeCellRenderer,
    private val contextMenuBuilder: TaskContextMenuBuilder,
    private val onEditTask: (Task) -> Unit,
    private val getSelectedTasks: () -> List<Task>
) {
    /**
     * Sets up all mouse listeners on the tree.
     */
    fun setup() {
        setupClickListener()
        setupMotionListener()
        setupExitListener()
    }

    private fun setupClickListener() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Skip if click was on checkbox (handled by TaskTree)
                if (tree.isOverCheckbox(e.x, e.y)) {
                    return
                }

                if (e.clickCount == 1 && e.button == MouseEvent.BUTTON1) {
                    if (handleDescriptionIndicatorClick(e)) {
                        return
                    }
                    if (handleLocationClick(e)) {
                        return
                    }
                    // Toggle selection if task was already selected before the click
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
    }

    private fun setupMotionListener() {
        tree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val isOverLink = LocationLinkDetector.isMouseOverLink(tree, renderer, e)
                val isOverDescriptionIndicator = DescriptionIndicatorDetector.isMouseOverIndicator(tree, renderer, e)
                tree.cursor = if (isOverLink || isOverDescriptionIndicator) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }

                // Update hovered row for highlight
                val newHoveredRow = tree.getRowForLocation(e.x, e.y)
                if (renderer.hoveredRow != newHoveredRow) {
                    renderer.hoveredRow = newHoveredRow
                    tree.repaint()
                }
            }
        })
    }

    private fun setupExitListener() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (renderer.hoveredRow != -1) {
                    renderer.hoveredRow = -1
                    tree.repaint()
                }
            }
        })
    }

    /**
     * Handles click on a description indicator. Returns true if indicator was clicked.
     */
    private fun handleDescriptionIndicatorClick(e: MouseEvent): Boolean {
        val task = DescriptionIndicatorDetector.getClickedTask(tree, renderer, e)
        if (task != null) {
            onEditTask(task)
            return true
        }
        return false
    }

    /**
     * Handles click on a code location link. Returns true if a link was clicked.
     */
    private fun handleLocationClick(e: MouseEvent): Boolean {
        val location = LocationLinkDetector.getClickedLocation(tree, renderer, e)
        if (location != null) {
            CodeLocationUtil.navigateToLocation(project, location)
            return true
        }
        return false
    }

    /**
     * Handles double-click to edit a task.
     */
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
            onEditTask(task)
        }
    }

    /**
     * Shows context menu on right-click if appropriate.
     */
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

    /**
     * Checks if mouse is over a location link. Used for tooltip display.
     */
    fun isMouseOverLocationLink(e: MouseEvent): Boolean {
        return LocationLinkDetector.isMouseOverLink(tree, renderer, e)
    }
}
