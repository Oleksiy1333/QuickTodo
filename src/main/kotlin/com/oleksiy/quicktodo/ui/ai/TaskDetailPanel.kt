package com.oleksiy.quicktodo.ui.ai

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.model.ClaudeExecutionMode
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.ui.QuickTodoIcons
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

/**
 * Right panel showing task details and execution mode selector.
 * Shows empty state when no task is focused.
 */
class TaskDetailPanel : JPanel(CardLayout()) {

    private var currentTask: Task? = null
    private var onModeChanged: ((String, ClaudeExecutionMode) -> Unit)? = null

    // Fixed panel width to prevent resizing
    private val PANEL_WIDTH = JBUI.scale(280)

    // Card names
    private val CARD_EMPTY = "empty"
    private val CARD_DETAILS = "details"
    private val CARD_DESC_CONTENT = "desc_content"
    private val CARD_DESC_EMPTY = "desc_empty"

    // Detail panel components
    private val taskNameLabel = JBLabel().apply {
        font = font.deriveFont(font.size2D + 1f).deriveFont(java.awt.Font.BOLD)
    }
    private val priorityIcon = JBLabel()
    private val descriptionArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty()
    }
    private val modeComboBox = ComboBox(ClaudeExecutionMode.entries.toTypedArray()).apply {
        renderer = ExecutionModeRenderer()
    }
    private val noDescriptionLabel = JBLabel("(No description)").apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        font = JBUI.Fonts.smallFont()
    }

    // Description content panel with CardLayout for switching between content/empty states
    private lateinit var descriptionContentPanel: JPanel

    init {
        border = JBUI.Borders.empty(12)
        background = JBColor.namedColor("Panel.background", JBColor.PanelBackground)

        // Create empty state panel
        val emptyPanel = createEmptyStatePanel()
        add(emptyPanel, CARD_EMPTY)

        // Create details panel
        val detailsPanel = createDetailsPanel()
        add(detailsPanel, CARD_DETAILS)

        // Show empty state by default
        showEmptyState()

        // Wire up mode change listener
        modeComboBox.addActionListener {
            val task = currentTask ?: return@addActionListener
            val mode = modeComboBox.selectedItem as? ClaudeExecutionMode ?: return@addActionListener
            onModeChanged?.invoke(task.id, mode)
        }
    }

    // Override size methods to maintain consistent width
    override fun getPreferredSize(): java.awt.Dimension {
        val superSize = super.getPreferredSize()
        return java.awt.Dimension(PANEL_WIDTH, superSize.height)
    }

    override fun getMinimumSize(): java.awt.Dimension {
        return java.awt.Dimension(PANEL_WIDTH, JBUI.scale(200))
    }

    private fun createEmptyStatePanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            isOpaque = false

            val messagePanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false

                val line1 = JBLabel("Click a task to see its").apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    alignmentX = CENTER_ALIGNMENT
                }
                val line2 = JBLabel("details and configure").apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    alignmentX = CENTER_ALIGNMENT
                }
                val line3 = JBLabel("execution mode").apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                    alignmentX = CENTER_ALIGNMENT
                }

                add(line1)
                add(Box.createVerticalStrut(4))
                add(line2)
                add(Box.createVerticalStrut(4))
                add(line3)
            }

            add(messagePanel, GridBagConstraints())
        }
    }

    private fun createDetailsPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false

            // Top section: Task name with priority
            val headerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(12)

                val namePanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(priorityIcon)
                    add(Box.createHorizontalStrut(6))
                    add(taskNameLabel)
                    add(Box.createHorizontalGlue())
                }
                add(namePanel, BorderLayout.CENTER)
            }

            // Middle section: Description (no label, no border)
            val descriptionPanel = JPanel(BorderLayout()).apply {
                isOpaque = false

                // CardLayout panel to switch between content and empty states
                descriptionContentPanel = JPanel(CardLayout()).apply {
                    isOpaque = false

                    // Card 1: Scroll pane with actual description content (no border)
                    val descScrollPane = JBScrollPane(descriptionArea).apply {
                        border = JBUI.Borders.empty()
                        preferredSize = java.awt.Dimension(0, JBUI.scale(120))
                    }
                    add(descScrollPane, CARD_DESC_CONTENT)

                    // Card 2: Simple label for empty state
                    val emptyDescPanel = JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(noDescriptionLabel, BorderLayout.NORTH)
                    }
                    add(emptyDescPanel, CARD_DESC_EMPTY)
                }
                add(descriptionContentPanel, BorderLayout.CENTER)
            }

            // Bottom section: Execution mode
            val modePanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(16)

                add(JSeparator(), BorderLayout.NORTH)

                val modeContentPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(12)

                    val modeLabel = JBLabel("Execution Mode").apply {
                        border = JBUI.Borders.emptyBottom(6)
                    }
                    add(modeLabel, BorderLayout.NORTH)
                    add(modeComboBox, BorderLayout.CENTER)
                }
                add(modeContentPanel, BorderLayout.CENTER)
            }

            // Assemble
            add(headerPanel, BorderLayout.NORTH)

            val contentPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(descriptionPanel, BorderLayout.CENTER)
                add(modePanel, BorderLayout.SOUTH)
            }
            add(contentPanel, BorderLayout.CENTER)
        }
    }

    fun showTask(task: Task, mode: ClaudeExecutionMode) {
        currentTask = task

        // Update task name
        taskNameLabel.text = task.text

        // Update priority icon
        val priority = task.getPriorityEnum()
        val icon = QuickTodoIcons.getIconForPriority(priority)
        priorityIcon.icon = icon
        priorityIcon.isVisible = icon != null

        // Update description - switch between content and empty cards
        val descLayout = descriptionContentPanel.layout as CardLayout
        if (task.hasDescription()) {
            descriptionArea.text = task.description
            descriptionArea.caretPosition = 0
            descriptionArea.foreground = null // Reset to default foreground
            descLayout.show(descriptionContentPanel, CARD_DESC_CONTENT)
        } else {
            descLayout.show(descriptionContentPanel, CARD_DESC_EMPTY)
        }

        // Update mode (without triggering listener)
        modeComboBox.removeActionListener(modeComboBox.actionListeners.firstOrNull())
        modeComboBox.selectedItem = mode
        modeComboBox.addActionListener {
            val t = currentTask ?: return@addActionListener
            val m = modeComboBox.selectedItem as? ClaudeExecutionMode ?: return@addActionListener
            onModeChanged?.invoke(t.id, m)
        }

        // Show details card
        (layout as CardLayout).show(this, CARD_DETAILS)
    }

    fun showEmptyState() {
        currentTask = null
        (layout as CardLayout).show(this, CARD_EMPTY)
    }

    fun setOnModeChanged(listener: (String, ClaudeExecutionMode) -> Unit) {
        onModeChanged = listener
    }

    fun getCurrentTaskId(): String? = currentTask?.id
}
