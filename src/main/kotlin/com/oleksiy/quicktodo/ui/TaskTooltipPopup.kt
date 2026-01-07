package com.oleksiy.quicktodo.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.FocusService
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Custom popup that shows task details with clickable code location link.
 */
class TaskTooltipPopup(
    private val project: Project,
    private val focusService: FocusService
) {
    private var currentPopup: JWindow? = null
    private var hideTimer: Timer? = null
    private var isMouseOverPopup = false

    fun showTooltip(task: Task, mouseLocation: RelativePoint) {
        // Don't recreate if already visible
        if (currentPopup?.isVisible == true) {
            hideTimer?.stop()
            return
        }

        hideTooltip()

        val content = buildTooltipContent(task) ?: return

        val window = JWindow()
        window.isAlwaysOnTop = true
        window.contentPane = content

        // Position near mouse
        val screenPoint = mouseLocation.screenPoint
        window.pack()

        // Get the screen device where the mouse is located
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val screenBounds = ge.screenDevices
            .map { it.defaultConfiguration.bounds }
            .firstOrNull { bounds ->
                screenPoint.x >= bounds.x && screenPoint.x < bounds.x + bounds.width &&
                screenPoint.y >= bounds.y && screenPoint.y < bounds.y + bounds.height
            } ?: ge.defaultScreenDevice.defaultConfiguration.bounds

        var x = screenPoint.x + 10
        var y = screenPoint.y + 10

        // Adjust if tooltip would go off the right edge of screen
        if (x + window.width > screenBounds.x + screenBounds.width) {
            x = screenBounds.x + screenBounds.width - window.width - 5
        }

        // Adjust if tooltip would go off the bottom edge of screen
        if (y + window.height > screenBounds.y + screenBounds.height) {
            y = screenPoint.y - window.height - 10
        }

        window.setLocation(x, y)
        window.isVisible = true

        currentPopup = window

        // Don't start hide timer yet - only when mouse moves away
    }

    fun scheduleHide() {
        if (!isMouseOverPopup) {
            startHideTimer()
        }
    }

    private fun startHideTimer() {
        hideTimer?.stop()
        hideTimer = Timer(500) {
            if (!isMouseOverPopup) {
                hideTooltip()
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun hideTooltip() {
        hideTimer?.stop()
        hideTimer = null
        isMouseOverPopup = false
        currentPopup?.dispose()
        currentPopup = null
    }

    fun setMouseOverPopup(over: Boolean) {
        isMouseOverPopup = over
        if (over) {
            hideTimer?.stop()
        } else {
            startHideTimer()
        }
    }

    private fun buildTooltipContent(task: Task): JPanel? {
        val parts = mutableListOf<String>()

        // Add task text
        parts.add("<b>${escapeHtml(task.text)}</b>")

        // Add completion progress for parent tasks
        if (task.subtasks.isNotEmpty()) {
            val (completed, total) = countCompletionProgress(task)
            parts.add("Progress: $completed/$total")
        }

        // Add timer information
        if (focusService.hasAccumulatedTime(task.id)) {
            val timeStr = focusService.getFormattedTime(task.id)
            parts.add("Time: $timeStr")
        }

        // Add priority
        val priority = task.getPriorityEnum()
        if (priority != Priority.NONE) {
            parts.add("Priority: ${priority.displayName}")
        }

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBUI.CurrentTheme.Tooltip.borderColor()),
            JBUI.Borders.empty(8, 10)
        )
        panel.background = JBUI.CurrentTheme.Tooltip.background()

        // Add info text
        if (parts.isNotEmpty()) {
            val infoLabel = JBLabel("<html><div style='width: 280px;'>${parts.joinToString("<br>")}</div></html>")
            infoLabel.foreground = JBUI.CurrentTheme.Tooltip.foreground()
            infoLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(infoLabel)
        }

        // Add clickable code location link
        if (task.hasCodeLocation()) {
            if (parts.isNotEmpty()) {
                panel.add(Box.createVerticalStrut(8))
            }

            val location = task.codeLocation!!
            val linkLabel = JBLabel("<html><div style='width: 280px;'><u>${escapeHtml(location.toDisplayString())}</u></div></html>")
            linkLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            linkLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            linkLabel.alignmentX = JComponent.LEFT_ALIGNMENT

            linkLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    CodeLocationUtil.navigateToLocation(project, location)
                    hideTooltip()
                }

                override fun mouseEntered(e: MouseEvent) {
                    linkLabel.foreground = JBUI.CurrentTheme.Link.Foreground.HOVERED
                }

                override fun mouseExited(e: MouseEvent) {
                    linkLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                }
            })

            panel.add(linkLabel)
        }

        // Keep popup visible when mouse is over it
        val mouseListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                setMouseOverPopup(true)
            }

            override fun mouseExited(e: MouseEvent) {
                setMouseOverPopup(false)
            }
        }
        panel.addMouseListener(mouseListener)

        // Also add to all child components
        for (component in panel.components) {
            component.addMouseListener(mouseListener)
        }

        return panel
    }

    private fun countCompletionProgress(task: Task): Pair<Int, Int> {
        var completed = 0
        var total = 0
        for (subtask in task.subtasks) {
            total++
            if (subtask.isCompleted) {
                completed++
            }
            val (nestedCompleted, nestedTotal) = countCompletionProgress(subtask)
            completed += nestedCompleted
            total += nestedTotal
        }
        return Pair(completed, total)
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
