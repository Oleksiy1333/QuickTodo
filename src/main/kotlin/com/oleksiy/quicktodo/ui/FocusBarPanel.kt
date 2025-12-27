package com.oleksiy.quicktodo.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.service.FocusService
import com.oleksiy.quicktodo.service.TaskService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

class FocusBarPanel(
    private val project: Project
) : JPanel(BorderLayout()), FocusService.FocusChangeListener, Disposable {

    private val focusService = FocusService.getInstance(project)
    private val taskService = TaskService.getInstance(project)

    private val focusLabel = JBLabel()
    private val timerLabel = JBLabel()

    init {
        isVisible = false
        background = FOCUS_BAR_BACKGROUND
        border = CompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10)
        )

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(focusLabel)
        }

        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(timerLabel)
        }

        add(leftPanel, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)

        focusService.addListener(this)
        updateDisplay()
    }

    override fun onFocusChanged(focusedTaskId: String?) {
        updateDisplay()
    }

    override fun onTimerTick() {
        updateTimerDisplay()
    }

    private fun updateDisplay() {
        val focusedId = focusService.getFocusedTaskId()
        if (focusedId == null) {
            isVisible = false
            return
        }

        val task = taskService.findTask(focusedId)
        if (task == null) {
            isVisible = false
            return
        }

        isVisible = true

        focusLabel.text = "FOCUS: ${task.text}"
        focusLabel.icon = QuickTodoIcons.Focus

        updateTimerDisplay()
    }

    private fun updateTimerDisplay() {
        val focusedId = focusService.getFocusedTaskId() ?: return
        val totalMs = focusService.getElapsedTime(focusedId)
        timerLabel.text = formatTimerDisplay(totalMs)
    }

    private fun formatTimerDisplay(totalMs: Long): String {
        val totalSeconds = totalMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun dispose() {
        focusService.removeListener(this)
    }

    companion object {
        private val FOCUS_BAR_BACKGROUND = JBColor(
            Color(255, 248, 220),  // Light theme: cream/light yellow
            Color(50, 45, 35)      // Dark theme: dark warm
        )
    }
}
