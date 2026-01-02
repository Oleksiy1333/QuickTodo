package com.oleksiy.quicktodo.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.service.DailyTaskStatsService
import com.oleksiy.quicktodo.service.TaskService
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Footer panel showing daily task statistics.
 * Displays "+N" (blue) for tasks created today and checkmark+M (green) for completed today.
 */
class DailyStatsPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val taskService = TaskService.getInstance(project)
    private val statsService = DailyTaskStatsService.getInstance(project)

    private val createdLabel = JBLabel().apply {
        foreground = CREATED_COLOR
        toolTipText = "Tasks added today"
    }

    private val completedLabel = JBLabel().apply {
        foreground = COMPLETED_COLOR
        toolTipText = "Tasks completed today"
    }

    private var taskListener: (() -> Unit)? = null

    init {
        border = JBUI.Borders.empty(2, 8, 4, 8)
        background = null
        isOpaque = false

        val statsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            background = null
            add(createdLabel)
            add(Box.createHorizontalStrut(8))
            add(completedLabel)
        }

        add(statsPanel, BorderLayout.EAST)

        taskListener = { SwingUtilities.invokeLater { updateStats() } }
        taskService.addListener(taskListener!!)

        updateStats()
    }

    private fun updateStats() {
        val stats = statsService.calculateDailyStats()

        createdLabel.text = if (stats.createdToday > 0) "+${stats.createdToday}" else ""
        createdLabel.isVisible = stats.createdToday > 0

        completedLabel.text = if (stats.completedToday > 0) "\u2714${stats.completedToday}" else ""
        completedLabel.isVisible = stats.completedToday > 0
    }

    override fun dispose() {
        taskListener?.let { taskService.removeListener(it) }
        taskListener = null
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
