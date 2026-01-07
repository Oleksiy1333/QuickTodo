package com.oleksiy.quicktodo.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.model.AutomationState
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.AutomationService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder

/**
 * Status bar panel showing Claude automation progress.
 * Displays current state, task name, and control buttons.
 */
class AutomationBarPanel(
    private val project: Project
) : JPanel(BorderLayout()), AutomationService.AutomationListener, Disposable {

    private val automationService = AutomationService.getInstance(project)

    private val statusLabel = JBLabel()
    private val taskLabel = JBLabel()
    private val pauseResumeButton = JButton()
    private val stopButton = JButton()

    init {
        isVisible = false
        background = AUTOMATION_BAR_BACKGROUND
        border = CompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 10)
        )

        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            statusLabel.alignmentY = CENTER_ALIGNMENT
            taskLabel.alignmentY = CENTER_ALIGNMENT
            add(statusLabel)
            add(Box.createHorizontalStrut(8))
            add(taskLabel)
        }

        setupButtons()

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            pauseResumeButton.alignmentY = CENTER_ALIGNMENT
            stopButton.alignmentY = CENTER_ALIGNMENT
            add(pauseResumeButton)
            add(Box.createHorizontalStrut(4))
            add(stopButton)
        }

        add(leftPanel, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)

        automationService.addListener(this)
        updateDisplay()
    }

    private fun setupButtons() {
        pauseResumeButton.apply {
            icon = QuickTodoIcons.AutomationPause
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            preferredSize = Dimension(24, 24)
            toolTipText = "Pause automation"
            addActionListener {
                when (automationService.getState()) {
                    AutomationState.WORKING -> {
                        automationService.pause()
                    }
                    AutomationState.PAUSED -> {
                        automationService.resume()
                    }
                    else -> {}
                }
            }
        }

        stopButton.apply {
            icon = QuickTodoIcons.AutomationStop
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            preferredSize = Dimension(24, 24)
            toolTipText = "Stop automation"
            addActionListener {
                automationService.stop()
            }
        }
    }

    override fun onStateChanged(state: AutomationState) {
        SwingUtilities.invokeLater {
            updateDisplay()
        }
    }

    override fun onCurrentTaskChanged(task: Task?) {
        SwingUtilities.invokeLater {
            updateTaskDisplay(task)
        }
    }

    override fun onError(message: String) {
        // Errors are handled by the service, we just update display
        SwingUtilities.invokeLater {
            updateDisplay()
        }
    }

    private fun updateDisplay() {
        val state = automationService.getState()

        when (state) {
            AutomationState.IDLE -> {
                isVisible = false
            }
            AutomationState.WORKING -> {
                isVisible = true
                statusLabel.text = "Building:"
                statusLabel.icon = QuickTodoIcons.Claude
                updateButtonForRunning()
            }
            AutomationState.PAUSED -> {
                isVisible = true
                statusLabel.text = "Paused:"
                statusLabel.icon = AllIcons.Actions.Pause
                updateButtonForPaused()
            }
        }

        updateTaskDisplay(automationService.getCurrentTask())
    }

    private fun updateTaskDisplay(task: Task?) {
        if (task != null) {
            taskLabel.text = task.text
            taskLabel.toolTipText = task.text
        } else {
            taskLabel.text = ""
            taskLabel.toolTipText = null
        }
    }

    private fun updateButtonForRunning() {
        pauseResumeButton.icon = QuickTodoIcons.AutomationPause
        pauseResumeButton.toolTipText = "Pause automation (finishes current operation)"
    }

    private fun updateButtonForPaused() {
        pauseResumeButton.icon = QuickTodoIcons.AutomationResume
        pauseResumeButton.toolTipText = "Resume automation"
    }

    override fun dispose() {
        automationService.removeListener(this)
    }

    companion object {
        private val AUTOMATION_BAR_BACKGROUND = JBColor(
            Color(230, 240, 255),  // Light theme: light blue
            Color(35, 45, 55)      // Dark theme: dark blue
        )
    }
}
