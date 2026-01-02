package com.oleksiy.quicktodo.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.service.DailyTaskStatsService
import com.oleksiy.quicktodo.service.FocusService
import com.oleksiy.quicktodo.service.TaskService
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Footer panel showing daily task statistics.
 * Displays focus time (amber) on left, "+N" (blue) for tasks created today,
 * and checkmark+M (green) for completed today on right.
 */
class DailyStatsPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val taskService = TaskService.getInstance(project)
    private val focusService = FocusService.getInstance(project)
    private val statsService = DailyTaskStatsService.getInstance(project)

    private val focusTimeLabel = JBLabel().apply {
        foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
    }

    private val createdLabel = JBLabel().apply {
        foreground = CREATED_COLOR
        toolTipText = "Tasks added today"
    }

    private val completedLabel = JBLabel().apply {
        foreground = COMPLETED_COLOR
        toolTipText = "Tasks completed today"
    }

    private val totalLabel = JBLabel().apply {
        foreground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
        toolTipText = "Total tasks"
    }

    private var taskListener: (() -> Unit)? = null
    private var focusListener: FocusService.FocusChangeListener? = null

    init {
        border = JBUI.Borders.empty(2, 8, 4, 8)
        background = null
        isOpaque = false

        val statsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            background = null
            add(completedLabel)
            add(Box.createHorizontalStrut(8))
            add(createdLabel)
            add(Box.createHorizontalStrut(8))
            add(totalLabel)
        }

        add(focusTimeLabel, BorderLayout.WEST)
        add(statsPanel, BorderLayout.EAST)

        taskListener = {
            SwingUtilities.invokeLater {
                updateStats()
                updateFocusTime()
            }
        }
        taskService.addListener(taskListener!!)

        focusListener = object : FocusService.FocusChangeListener {
            override fun onFocusChanged(focusedTaskId: String?) {
                SwingUtilities.invokeLater { updateFocusTime() }
            }

            override fun onTimerTick() {
                SwingUtilities.invokeLater { updateFocusTime() }
            }
        }
        focusService.addListener(focusListener!!)

        updateStats()
        updateFocusTime()
    }

    private fun updateStats() {
        val stats = statsService.calculateDailyStats()
        val totalTasks = countAllTasks()

        createdLabel.text = if (stats.createdToday > 0) "+${stats.createdToday}" else ""
        createdLabel.isVisible = stats.createdToday > 0

        completedLabel.text = if (stats.completedToday > 0) "\u2714${stats.completedToday}" else ""
        completedLabel.isVisible = stats.completedToday > 0

        totalLabel.text = "#$totalTasks"
        totalLabel.isVisible = totalTasks > 0
    }

    private fun countAllTasks(): Int {
        var count = 0
        fun traverse(task: com.oleksiy.quicktodo.model.Task) {
            count++
            task.subtasks.forEach { traverse(it) }
        }
        taskService.getTasks().forEach { traverse(it) }
        return count
    }

    private fun updateFocusTime() {
        val accumulatedMs = taskService.getTodayFocusTime()
        val currentSessionMs = getCurrentSessionTime()
        val totalMs = accumulatedMs + currentSessionMs

        if (totalMs > 0) {
            focusTimeLabel.text = "\u23F1 ${formatTimeShort(totalMs)}"
            focusTimeLabel.toolTipText = "Focus time today: ${formatTimeFull(totalMs)}"
            focusTimeLabel.isVisible = true
        } else {
            focusTimeLabel.isVisible = false
        }
    }

    private fun getCurrentSessionTime(): Long {
        val focusedTaskId = focusService.getFocusedTaskId() ?: return 0
        if (!focusService.isRunning()) return 0

        val task = taskService.findTask(focusedTaskId) ?: return 0
        val focusStart = task.lastFocusStartedAt ?: return 0
        return System.currentTimeMillis() - focusStart
    }

    private fun formatTimeShort(ms: Long): String {
        val totalMinutes = ms / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }

    private fun formatTimeFull(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    override fun dispose() {
        taskListener?.let { taskService.removeListener(it) }
        taskListener = null
        focusListener?.let { focusService.removeListener(it) }
        focusListener = null
    }

    companion object {
        private val CREATED_COLOR = JBColor(
            Color(0x58, 0x9D, 0xF6),  // Light theme blue
            Color(0x58, 0x9D, 0xF6)   // Dark theme blue
        )
        private val COMPLETED_COLOR = JBColor(
            Color(0x59, 0xA8, 0x69),  // Light theme green
            Color(0x49, 0x9C, 0x54)   // Dark theme green
        )
    }
}
