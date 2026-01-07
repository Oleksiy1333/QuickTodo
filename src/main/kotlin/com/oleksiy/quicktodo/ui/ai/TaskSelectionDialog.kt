package com.oleksiy.quicktodo.ui.ai

import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
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
import javax.swing.JLabel
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

            // Experimental badge
            val experimentalLabel = JBLabel("Experimental").apply {
                foreground = JBUI.CurrentTheme.NotificationWarning.foregroundColor()
                font = font.deriveFont(font.size2D - 1f)
                border = JBUI.Borders.empty(2, 6)
                isOpaque = true
                background = JBUI.CurrentTheme.NotificationWarning.backgroundColor()
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            add(experimentalLabel)
            add(Box.createVerticalStrut(JBUI.scale(8)))

            // Title row with description and flow info icon
            val titleRow = JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT

                val descLabel = JBLabel("Select tasks and configure execution mode for each.").apply {
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                }
                add(descLabel, BorderLayout.CENTER)

                val flowInfoIcon = createHelpIcon(
                    "How Build with Claude Works",
                    "<p>1. Select tasks you want Claude to implement</p>" +
                    "<p>2. Configure execution mode for each task (or use default)</p>" +
                    "<p>3. Click Start — tasks are processed sequentially</p>" +
                    "<p>4. Claude Code runs in your terminal with the configured permissions</p>" +
                    "<p>5. After each task completes, it's marked as done</p>" +
                    "<p></p>" +
                    "<p><i>Tasks are processed from top to bottom.</i></p>"
                )
                add(flowInfoIcon, BorderLayout.EAST)
            }
            add(titleRow)

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

                add(Box.createHorizontalStrut(JBUI.scale(4)))
                val defaultModeHelp = createHelpIcon(
                    "Default Mode",
                    "<p>The execution mode applied to all tasks unless overridden. " +
                    "Click a task on the left to set a specific mode for that task only.</p>" +
                    "<p></p>" +
                    "<p><b>Execution Modes:</b></p>" +
                    "<p>• <b>Plan</b> — Interactive review where you approve each change</p>" +
                    "<p>• <b>Accept Edits</b> — Auto-accepts file operations (Edit, Write, Read)</p>" +
                    "<p>• <b>Skip Permissions</b> — No permission checks (use with caution)</p>"
                )
                add(defaultModeHelp)
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

            // Auto-continue checkbox with help icon
            val autoContinuePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT
                add(autoContinueCheckbox)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(createHelpIcon(
                    "Auto-continue",
                    "When enabled, automatically proceeds to the next task after Claude completes the current one. " +
                    "When disabled, shows a confirmation dialog between tasks."
                ))
            }
            add(autoContinuePanel)

            add(Box.createVerticalStrut(JBUI.scale(4)))

            // Ask more questions checkbox with help icon
            val askMoreQuestionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT
                add(askMoreQuestionsCheckbox)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(createHelpIcon(
                    "Ask More Questions",
                    "When enabled, Claude will ask clarifying questions before implementing each task. " +
                    "Recommended for complex tasks where requirements may be ambiguous."
                ))
            }
            add(askMoreQuestionsPanel)
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

    private fun createHelpIcon(title: String, description: String): JLabel {
        return JLabel(AllIcons.General.ContextHelp).apply {
            HelpTooltip()
                .setTitle(title)
                .setDescription(description)
                .installOn(this)
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
