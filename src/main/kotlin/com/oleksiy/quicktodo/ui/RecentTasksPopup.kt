package com.oleksiy.quicktodo.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.LayeredIcon
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.FocusService
import com.oleksiy.quicktodo.service.TaskService
import com.oleksiy.quicktodo.settings.QuickTodoSettings
import java.awt.Container
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.JList

class RecentTasksPopup(private val project: Project) {

    fun show() {
        val taskService = TaskService.getInstance(project)
        val focusService = FocusService.getInstance(project)
        val settings = QuickTodoSettings.getInstance()

        // Get all tasks flattened (no hierarchy)
        val allTasks = mutableListOf<Task>()
        fun collectTasks(tasks: List<Task>) {
            tasks.forEach { task ->
                allTasks.add(task)
                collectTasks(task.subtasks)
            }
        }
        collectTasks(taskService.getTasks())

        // Sort by lastModified descending (fallback to createdAt if lastModified is 0)
        // Filter out completed tasks
        val recentTasks = allTasks
            .filter { !it.isCompleted }
            .sortedWith(compareByDescending<Task> {
                if (it.lastModified > 0) it.lastModified else (it.createdAt ?: 0L)
            })
            .take(settings.getRecentTasksCount())

        if (recentTasks.isEmpty()) {
            return
        }
        
        val step = object : BaseListPopupStep<Task>("Recent Todos", recentTasks) {
            override fun onChosen(selectedValue: Task?, finalChoice: Boolean): PopupStep<*>? {
                return doFinalStep {
                    if (selectedValue != null) {
                        // Default action: Focus on the task and select it
                        focusService.setFocus(selectedValue.id)
                        val panel = ChecklistPanel.getInstance(project)
                        panel?.selectTaskById(selectedValue.id)
                    }
                }
            }

            override fun hasSubstep(selectedValue: Task?): Boolean = false

            override fun getTextFor(value: Task): String {
                return value.text
            }

            override fun getIconFor(value: Task): Icon? {
                val priority = value.getPriorityEnum()
                val hasPriorityIcon = priority != Priority.NONE
                val hasCodeLocation = value.hasCodeLocation()

                // If both icons needed, combine them; otherwise return the one available
                return when {
                    hasPriorityIcon && hasCodeLocation -> {
                        // Combine priority flag and link icon
                        LayeredIcon(2).apply {
                            setIcon(QuickTodoIcons.getIconForPriority(priority), 0)
                            setIcon(AllIcons.General.Inline_edit, 1, 16, 0)
                        }
                    }
                    hasPriorityIcon -> QuickTodoIcons.getIconForPriority(priority)
                    hasCodeLocation -> AllIcons.General.Locate
                    else -> null
                }
            }

            override fun isSpeedSearchEnabled(): Boolean = true
        }

        val popup = JBPopupFactory.getInstance()
            .createListPopup(step)
            .apply {
                setMinimumSize(Dimension(400, 0))
            }

        // Get JList from popup: content -> JBScrollPane -> JBViewport -> ListPopupImpl$MyList
        fun getListComponent(): JList<*>? {
            val scrollPane = popup.content.components.getOrNull(0) as? Container ?: return null
            val viewport = scrollPane.components.getOrNull(0) as? Container ?: return null
            return viewport.components.getOrNull(0) as? JList<*>
        }

        val keyEventDispatcher = KeyEventDispatcher { e ->
            if (popup.isVisible &&
                e.id == KeyEvent.KEY_PRESSED &&
                ((e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) ||
                 (e.keyCode == KeyEvent.VK_ENTER && e.isMetaDown))) {

                // Get the list component at the time of the key press
                val listComponent = getListComponent()
                val leadSelectionIndex = listComponent?.selectionModel?.leadSelectionIndex ?: -1

                // Get selected task from model using lead selection index
                val selectedTask = if (leadSelectionIndex >= 0 && listComponent?.model != null && leadSelectionIndex < listComponent.model.size) {
                    listComponent.model.getElementAt(leadSelectionIndex) as? Task
                } else {
                    null
                }

                if (selectedTask?.hasCodeLocation() == true) {
                    selectedTask.codeLocation?.let { location ->
                        CodeLocationUtil.navigateToLocation(project, location)
                    }
                    popup.cancel()
                    return@KeyEventDispatcher true // consume event
                }
            }
            false // don't consume event
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
            }
        })

        popup.showCenteredInCurrentWindow(project)
    }
}
