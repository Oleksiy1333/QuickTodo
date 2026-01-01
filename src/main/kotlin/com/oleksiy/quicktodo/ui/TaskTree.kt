package com.oleksiy.quicktodo.ui

import com.intellij.ui.CheckedTreeNode
import com.oleksiy.quicktodo.model.Task
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke

/**
 * A custom JTree for displaying tasks with checkboxes.
 * Unlike CheckboxTree, this gives us full control over checkbox behavior
 * without any automatic parent/child state propagation.
 */
open class TaskTree(
    private val onTaskToggled: (task: Task, isChecked: Boolean) -> Unit
) : JTree() {

    // Track checkbox press to handle slight mouse movement between press and click
    private var checkboxPressedOnTask: Task? = null

    // Track if the clicked path was selected before mouse press (for toggle selection)
    var pathWasSelectedBeforePress = false
        private set

    init {
        setupKeyboardToggle()
        // Disable default double-click expand/collapse behavior
        toggleClickCount = 0
    }

    /**
     * Handle mouse events to detect checkbox clicks.
     * When user clicks on the checkbox area, toggle the task completion.
     */
    override fun processMouseEvent(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON1) {
            when (e.id) {
                MouseEvent.MOUSE_PRESSED -> {
                    // Capture selection state BEFORE super.processMouseEvent changes it
                    if (!isOverCheckbox(e.x, e.y)) {
                        // Use getClosestRowForLocation to detect row even on empty space
                        val row = getClosestRowForLocation(e.x, e.y)
                        val rowBounds = if (row >= 0) getRowBounds(row) else null
                        // Verify click is within actual row bounds
                        val isWithinRowBounds = rowBounds != null &&
                            e.y >= rowBounds.y && e.y < rowBounds.y + rowBounds.height
                        pathWasSelectedBeforePress = isWithinRowBounds && isRowSelected(row)
                    }

                    if (isOverCheckbox(e.x, e.y)) {
                        // Remember which task's checkbox was pressed
                        checkboxPressedOnTask = getTaskAtLocation(e.x, e.y)
                        e.consume()
                        return
                    }
                }
                MouseEvent.MOUSE_RELEASED -> {
                    if (checkboxPressedOnTask != null) {
                        // Complete the checkbox toggle if we started on a checkbox
                        val task = checkboxPressedOnTask!!
                        checkboxPressedOnTask = null

                        val path = getPathForRowAt(e.x, e.y)
                        val node = path?.lastPathComponent as? CheckedTreeNode
                        val releasedTask = node?.userObject as? Task

                        // Toggle if released on the same task (allows slight mouse movement)
                        if (releasedTask?.id == task.id) {
                            node.isChecked = !node.isChecked
                            onTaskToggled(task, node.isChecked)
                            val row = getRowForPath(path)
                            if (row >= 0) {
                                getRowBounds(row)?.let { repaint(it) }
                            }
                        }
                        e.consume()
                        return
                    }
                }
                MouseEvent.MOUSE_CLICKED -> {
                    // Consume click if it was part of a checkbox interaction
                    if (isOverCheckbox(e.x, e.y)) {
                        e.consume()
                        return
                    }
                }
            }
        }
        super.processMouseEvent(e)
    }

    /**
     * Setup Space key to toggle checkbox of selected task.
     */
    private fun setupKeyboardToggle() {
        val toggleAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val path = selectionPath ?: return
                val node = path.lastPathComponent as? CheckedTreeNode ?: return
                val task = node.userObject as? Task ?: return

                val newCheckedState = !node.isChecked
                node.isChecked = newCheckedState
                onTaskToggled(task, newCheckedState)

                val row = getRowForPath(path)
                if (row >= 0) {
                    getRowBounds(row)?.let { repaint(it) }
                }
            }
        }

        getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
            "toggleCheckbox"
        )
        actionMap.put("toggleCheckbox", toggleAction)
    }

    /**
     * Gets the TreePath for the row at the given location.
     * Unlike getPathForLocation, this works anywhere on the row, not just over rendered content.
     * Returns null if the location is not within any row's vertical bounds.
     */
    fun getPathForRowAt(x: Int, y: Int): javax.swing.tree.TreePath? {
        val row = getClosestRowForLocation(x, y)
        if (row < 0) return null

        val rowBounds = getRowBounds(row) ?: return null
        if (y < rowBounds.y || y >= rowBounds.y + rowBounds.height) {
            return null
        }

        return getPathForRow(row)
    }

    /**
     * Get the task at a specific location (for context menus, etc.)
     * Works anywhere on the row, not just over rendered content.
     */
    fun getTaskAtLocation(x: Int, y: Int): Task? {
        val path = getPathForRowAt(x, y) ?: return null
        val node = path.lastPathComponent as? CheckedTreeNode ?: return null
        return node.userObject as? Task
    }

    /**
     * Check if coordinates are over the checkbox area.
     * The checkbox starts at rowBounds.x (after the expand/collapse arrow).
     */
    fun isOverCheckbox(x: Int, y: Int): Boolean {
        val path = getPathForRowAt(x, y) ?: return false
        val row = getRowForPath(path)
        val rowBounds = getRowBounds(row) ?: return false
        val checkboxEndX = rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH + 4
        return x >= rowBounds.x && x <= checkboxEndX
    }
}
