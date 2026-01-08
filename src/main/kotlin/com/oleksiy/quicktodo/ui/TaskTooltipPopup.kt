package com.oleksiy.quicktodo.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.FocusService
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Custom popup that shows task details with clickable code location link.
 * Uses JetBrains ComponentPopupBuilder for native styling with rounded corners.
 */
class TaskTooltipPopup(
    private val project: Project,
    private val focusService: FocusService
) {
    private var currentPopup: JBPopup? = null
    private var hideTimer: Timer? = null
    private var isMouseOverPopup = false

    fun showTooltip(task: Task, mouseLocation: RelativePoint) {
        // Don't recreate if already visible
        if (currentPopup?.isDisposed == false) {
            hideTimer?.stop()
            return
        }

        hideTooltip()

        val content = buildTooltipContent(task) ?: return

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setShowBorder(true)
            .setShowShadow(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelOnWindowDeactivation(true)
            .setRequestFocus(false)
            .setFocusable(false)
            .setLocateWithinScreenBounds(true)
            .createPopup()

        popup.show(mouseLocation)
        currentPopup = popup
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
        currentPopup?.cancel()
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
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(8, 10)

        // Header row: Priority icon + Task title
        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.X_AXIS)
        headerPanel.isOpaque = false
        headerPanel.alignmentX = JComponent.LEFT_ALIGNMENT

        val priority = task.getPriorityEnum()
        val priorityIcon = QuickTodoIcons.getIconForPriority(priority)
        if (priorityIcon != null) {
            val iconLabel = JBLabel(priorityIcon)
            iconLabel.border = JBUI.Borders.emptyRight(6)
            headerPanel.add(iconLabel)
        }

        val titleLabel = JBLabel("<html><b>${escapeHtml(task.text)}</b></html>")
        headerPanel.add(titleLabel)
        headerPanel.add(Box.createHorizontalGlue())
        panel.add(headerPanel)

        // Description section (if present)
        if (task.hasDescription()) {
            panel.add(Box.createVerticalStrut(6))
            val descText = truncateDescription(task.description, 150)
            val descLabel = JBLabel("<html><div style='width: 260px;'>${escapeHtml(descText)}</div></html>")
            descLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            descLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(descLabel)
        }

        // Metadata row: Progress and Time
        val metadataParts = mutableListOf<String>()
        if (task.subtasks.isNotEmpty()) {
            val (completed, total) = countCompletionProgress(task)
            metadataParts.add("$completed/$total completed")
        }
        if (focusService.hasAccumulatedTime(task.id)) {
            val timeStr = focusService.getFormattedTime(task.id)
            metadataParts.add("\u23F1 $timeStr")
        }

        if (metadataParts.isNotEmpty()) {
            panel.add(Box.createVerticalStrut(6))
            val metadataLabel = JBLabel(metadataParts.joinToString("  \u2022  "))
            metadataLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            metadataLabel.alignmentX = JComponent.LEFT_ALIGNMENT
            panel.add(metadataLabel)
        }

        // Code location link (clickable)
        if (task.hasCodeLocation()) {
            panel.add(Box.createVerticalStrut(6))

            val location = task.codeLocation!!
            val linkLabel = JBLabel("<html><u>${escapeHtml(location.toDisplayString())}</u></html>")
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

    private fun truncateDescription(text: String, maxLength: Int): String {
        val cleaned = text.replace("\n", " ").replace("\r", "").trim()
        return if (cleaned.length > maxLength) {
            cleaned.take(maxLength).trimEnd() + "..."
        } else {
            cleaned
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
