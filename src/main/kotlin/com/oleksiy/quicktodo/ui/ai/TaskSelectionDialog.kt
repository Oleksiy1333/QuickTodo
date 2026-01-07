package com.oleksiy.quicktodo.ui.ai

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.oleksiy.quicktodo.model.ClaudeExecutionMode
import com.oleksiy.quicktodo.model.ClaudeModel
import com.oleksiy.quicktodo.model.TaskExecutionConfig
import com.oleksiy.quicktodo.service.AiConfigService
import com.oleksiy.quicktodo.service.TaskService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Dialog for selecting tasks to be processed by Claude Code.
 * Features a split-panel layout with task list on the left and details on the right.
 */
class TaskSelectionDialog(
    private val project: Project
) : DialogWrapper(project) {

    private val taskService = TaskService.getInstance(project)
    private val aiConfigService = AiConfigService.getInstance(project)

    // Panels
    private lateinit var taskListPanel: TaskListPanel
    private lateinit var taskDetailPanel: TaskDetailPanel

    // Configuration controls (bulk selector excludes DEFAULT option)
    private val bulkModeComboBox = ComboBox(ClaudeExecutionMode.bulkModes()).apply {
        renderer = ExecutionModeRenderer()
        selectedItem = ClaudeExecutionMode.PLAN
    }
    private val modelComboBox = ComboBox(ClaudeModel.entries.toTypedArray()).apply {
        selectedItem = aiConfigService.getSelectedModel()
    }
    private val autoContinueCheckbox = JBCheckBox("Auto-continue to next task", true)
    private val askMoreQuestionsCheckbox = JBCheckBox("Ask more questions", false)

    // Track execution modes per task
    private val taskModes = mutableMapOf<String, ClaudeExecutionMode>()

    init {
        title = "Build with Claude"
        setOKButtonText("Start")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(700), JBUI.scale(500))
            border = JBUI.Borders.empty(12)
        }

        // Header panel
        val headerPanel = createHeaderPanel()

        // Split panel with task list and details
        val splitPanel = createSplitPanel()

        // Bottom panel with options
        val bottomPanel = createBottomPanel()

        mainPanel.add(headerPanel, BorderLayout.NORTH)
        mainPanel.add(splitPanel, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyBottom(12)

            // Description text
            val descLabel = JBLabel("Select tasks and configure execution mode for each.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            add(descLabel)

            add(Box.createVerticalStrut(JBUI.scale(12)))

            // Model and mode selectors
            val selectorsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT

                // Model selector
                val modelLabel = JBLabel("Model:")
                add(modelLabel)
                add(Box.createHorizontalStrut(JBUI.scale(8)))

                modelComboBox.preferredSize = Dimension(JBUI.scale(150), modelComboBox.preferredSize.height)
                add(modelComboBox)

                add(Box.createHorizontalStrut(JBUI.scale(24)))

                // Mode selector
                val modeLabel = JBLabel("Default mode:")
                add(modeLabel)
                add(Box.createHorizontalStrut(JBUI.scale(8)))

                bulkModeComboBox.preferredSize = Dimension(JBUI.scale(180), bulkModeComboBox.preferredSize.height)
                bulkModeComboBox.addActionListener {
                    applyBulkMode()
                }
                add(bulkModeComboBox)
            }
            add(selectorsPanel)
        }
    }

    private fun createSplitPanel(): JComponent {
        // Create task list panel (left)
        taskListPanel = TaskListPanel().apply {
            setTasks(taskService.getTasks())

            setOnTaskFocused { task ->
                val mode = taskModes[task.id] ?: ClaudeExecutionMode.DEFAULT
                taskDetailPanel.showTask(task, mode)
            }

            setOnSelectionChanged {
                // When selection changes, update modes for newly selected tasks
                val selectedIds = getSelectedTaskIds()

                for (taskId in selectedIds) {
                    if (!taskModes.containsKey(taskId)) {
                        // New tasks default to DEFAULT (inherit from bulk selector)
                        taskModes[taskId] = ClaudeExecutionMode.DEFAULT
                    }
                }

                // Remove modes for deselected tasks
                taskModes.keys.retainAll(selectedIds.toSet())
            }
        }

        // Create detail panel (right)
        taskDetailPanel = TaskDetailPanel().apply {
            setOnModeChanged { taskId, mode ->
                taskModes[taskId] = mode
            }
        }

        // Create splitter (60/40 ratio) with minimal divider
        val splitter = Splitter(false, 0.6f).apply {
            firstComponent = taskListPanel
            secondComponent = taskDetailPanel
            dividerWidth = 1
        }

        // Wrap entire splitter in a single bordered panel
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(splitter, BorderLayout.CENTER)
        }
    }

    private fun createBottomPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(12)

            add(JSeparator().apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(JBUI.scale(12)))

            autoContinueCheckbox.alignmentX = JComponent.LEFT_ALIGNMENT
            add(autoContinueCheckbox)

            add(Box.createVerticalStrut(JBUI.scale(4)))

            askMoreQuestionsCheckbox.alignmentX = JComponent.LEFT_ALIGNMENT
            add(askMoreQuestionsCheckbox)

            add(Box.createVerticalStrut(JBUI.scale(8)))

            val infoLabel = JBLabel("Tasks are processed from top to bottom.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            add(infoLabel)
        }
    }

    private fun applyBulkMode() {
        // No need to update taskModes - tasks with DEFAULT mode automatically use the bulk mode
        // Just refresh the detail panel if it's showing a task (to update tooltip/display)
        val currentTaskId = taskDetailPanel.getCurrentTaskId()
        if (currentTaskId != null) {
            val task = taskListPanel.findTask(currentTaskId)
            if (task != null) {
                val mode = taskModes[task.id] ?: ClaudeExecutionMode.DEFAULT
                taskDetailPanel.showTask(task, mode)
            }
        }
    }

    override fun doOKAction() {
        val selectedIds = taskListPanel.getSelectedTaskIds()
        if (selectedIds.isEmpty()) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "Please select at least one task to build.",
                "No Tasks Selected"
            )
            return
        }
        super.doOKAction()
    }

    // ============ Public Accessors ============

    fun getSelectedTaskIds(): List<String> = taskListPanel.getSelectedTaskIds()

    fun isAutoContinue(): Boolean = autoContinueCheckbox.isSelected

    fun isAskMoreQuestions(): Boolean = askMoreQuestionsCheckbox.isSelected

    fun getSelectedModel(): ClaudeModel = modelComboBox.selectedItem as? ClaudeModel ?: ClaudeModel.default()

    /**
     * Returns per-task execution configurations for all selected tasks.
     * Resolves DEFAULT mode to the actual bulk mode.
     */
    fun getTaskConfigs(): List<TaskExecutionConfig> {
        val bulkMode = bulkModeComboBox.selectedItem as? ClaudeExecutionMode
            ?: ClaudeExecutionMode.PLAN

        return taskListPanel.getSelectedTaskIds().map { taskId ->
            val taskMode = taskModes[taskId] ?: ClaudeExecutionMode.DEFAULT
            // Resolve DEFAULT to the actual bulk mode
            val resolvedMode = if (taskMode == ClaudeExecutionMode.DEFAULT) bulkMode else taskMode
            TaskExecutionConfig(
                taskId = taskId,
                executionMode = resolvedMode.name
            )
        }
    }
}
