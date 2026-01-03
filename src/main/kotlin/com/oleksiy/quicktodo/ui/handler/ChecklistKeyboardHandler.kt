package com.oleksiy.quicktodo.ui.handler

import com.oleksiy.quicktodo.model.Task
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.KeyStroke

/**
 * Handles keyboard shortcuts for the checklist tree.
 * Supports undo, redo, copy, add task, and move operations with platform-appropriate key bindings.
 */
class ChecklistKeyboardHandler(
    private val tree: JTree,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
    private val onAddTask: () -> Unit,
    private val getSelectedTasks: () -> List<Task>,
    private val onMoveUp: () -> Unit,
    private val onMoveDown: () -> Unit,
    private val canMoveUp: () -> Boolean,
    private val canMoveDown: () -> Boolean
) {
    /**
     * Sets up keyboard shortcuts on the tree.
     */
    fun setup() {
        val undoAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                onUndo()
            }
        }

        val redoAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                onRedo()
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

        val clearSelectionAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                tree.clearSelection()
            }
        }

        val addTaskAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                onAddTask()
            }
        }

        val moveUpAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (canMoveUp()) onMoveUp()
            }
        }

        val moveDownAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (canMoveDown()) onMoveDown()
            }
        }

        // Define move keystrokes
        val moveUpCtrl = KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.CTRL_DOWN_MASK)
        val moveUpMeta = KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.META_DOWN_MASK)
        val moveDownCtrl = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.CTRL_DOWN_MASK)
        val moveDownMeta = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.META_DOWN_MASK)

        // Clear any existing bindings for move shortcuts from ancestor maps
        // This is needed because JTree has default Cmd+Down binding on macOS
        tree.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).apply {
            put(moveUpCtrl, "none")
            put(moveUpMeta, "none")
            put(moveDownCtrl, "none")
            put(moveDownMeta, "none")
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
            // ESC: Clear selection
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSelection")
            // Add task: Ctrl+Enter (Windows/Linux) or Cmd+Enter (macOS)
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "addTask")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.META_DOWN_MASK), "addTask")
            // Move up: Ctrl+Up (Windows/Linux) or Cmd+Up (macOS)
            put(moveUpCtrl, "moveTaskUp")
            put(moveUpMeta, "moveTaskUp")
            // Move down: Ctrl+Down (Windows/Linux) or Cmd+Down (macOS)
            put(moveDownCtrl, "moveTaskDown")
            put(moveDownMeta, "moveTaskDown")
        }

        tree.actionMap.apply {
            put("undoTask", undoAction)
            put("redoTask", redoAction)
            put("copyTaskText", copyAction)
            put("clearSelection", clearSelectionAction)
            put("addTask", addTaskAction)
            put("moveTaskUp", moveUpAction)
            put("moveTaskDown", moveDownAction)
        }
    }
}
