package com.oleksiy.quicktodo.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

/**
 * Settings page for QuickTodo plugin under Tools > QuickTodo.
 */
class QuickTodoConfigurable : Configurable {
    private var settingsPanel: JPanel? = null
    private val radioButtons = mutableMapOf<TooltipBehavior, JRadioButton>()
    private lateinit var autoPauseCheckBox: JBCheckBox
    private lateinit var idleMinutesField: JBTextField
    private lateinit var recentTasksCountField: JBTextField
    private lateinit var accumulateHierarchyCheckBox: JBCheckBox

    override fun getDisplayName(): String = "QuickTodo"

    override fun createComponent(): JComponent {
        val settings = QuickTodoSettings.getInstance()

        // Create radio buttons for tooltip behavior
        val buttonGroup = ButtonGroup()
        for (behavior in TooltipBehavior.entries) {
            val radioButton = JRadioButton(behavior.displayName)
            radioButton.isSelected = settings.getTooltipBehavior() == behavior
            buttonGroup.add(radioButton)
            radioButtons[behavior] = radioButton
        }

        // Create autopause controls on one line
        autoPauseCheckBox = JBCheckBox("Focus timer pause if idle")
        autoPauseCheckBox.isSelected = settings.isAutoPauseEnabled()

        idleMinutesField = JBTextField(settings.getIdleMinutes().toString(), 5).apply {
            maximumSize = java.awt.Dimension(50, preferredSize.height)
        }

        val autoPausePanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            add(autoPauseCheckBox)
            add(javax.swing.Box.createHorizontalStrut(5))
            add(idleMinutesField)
            add(javax.swing.Box.createHorizontalStrut(5))
            add(JLabel("minutes"))
        }

        // Enable/disable idle minutes field based on checkbox
        idleMinutesField.isEnabled = autoPauseCheckBox.isSelected
        autoPauseCheckBox.addActionListener {
            idleMinutesField.isEnabled = autoPauseCheckBox.isSelected
        }

        // Create hierarchy accumulation checkbox
        accumulateHierarchyCheckBox = JBCheckBox("Accumulate hierarchy time")
        accumulateHierarchyCheckBox.isSelected = settings.isAccumulateHierarchyTime()

        // Create recent tasks count field
        recentTasksCountField = JBTextField(settings.getRecentTasksCount().toString(), 5)
        val recentTasksPanel = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 0)
            add(JLabel("Show"))
            add(recentTasksCountField)
            add(JLabel("recent tasks"))
        }

        settingsPanel = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator("Tooltip Behavior"))
            .addComponent(radioButtons[TooltipBehavior.ALWAYS]!!, 1)
            .addComponent(radioButtons[TooltipBehavior.TRUNCATED]!!, 1)
            .addComponent(radioButtons[TooltipBehavior.NEVER]!!, 1)
            .addVerticalGap(10)
            .addComponent(TitledSeparator("Time Tracking"))
            .addComponent(autoPausePanel, 0)
            .addComponent(accumulateHierarchyCheckBox, 1)
            .addVerticalGap(10)
            .addComponent(TitledSeparator("Recent Tasks"))
            .addComponent(recentTasksPanel, 0)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return settingsPanel!!
    }

    override fun isModified(): Boolean {
        val settings = QuickTodoSettings.getInstance()
        val currentBehavior = settings.getTooltipBehavior()

        val tooltipModified = radioButtons.entries.any { (behavior, button) ->
            button.isSelected && behavior != currentBehavior
        }

        val autoPauseModified = autoPauseCheckBox.isSelected != settings.isAutoPauseEnabled()

        val idleMinutesModified = try {
            idleMinutesField.text.toInt() != settings.getIdleMinutes()
        } catch (e: NumberFormatException) {
            false
        }

        val recentTasksCountModified = try {
            recentTasksCountField.text.toInt() != settings.getRecentTasksCount()
        } catch (_: NumberFormatException) {
            false
        }

        val accumulateHierarchyModified = accumulateHierarchyCheckBox.isSelected != settings.isAccumulateHierarchyTime()

        return tooltipModified || autoPauseModified || idleMinutesModified || recentTasksCountModified || accumulateHierarchyModified
    }

    override fun apply() {
        val settings = QuickTodoSettings.getInstance()

        radioButtons.forEach { (behavior, button) ->
            if (button.isSelected) {
                settings.setTooltipBehavior(behavior)
            }
        }

        settings.setAutoPauseEnabled(autoPauseCheckBox.isSelected)

        try {
            val minutes = idleMinutesField.text.toInt()
            settings.setIdleMinutes(minutes)
        } catch (e: NumberFormatException) {
            // Keep current value if invalid
        }

        try {
            val count = recentTasksCountField.text.toInt()
            settings.setRecentTasksCount(count)
        } catch (e: NumberFormatException) {
            // Keep current value if invalid
        }

        settings.setAccumulateHierarchyTime(accumulateHierarchyCheckBox.isSelected)
    }

    override fun reset() {
        val settings = QuickTodoSettings.getInstance()
        val currentBehavior = settings.getTooltipBehavior()

        radioButtons.forEach { (behavior, button) ->
            button.isSelected = behavior == currentBehavior
        }

        autoPauseCheckBox.isSelected = settings.isAutoPauseEnabled()
        idleMinutesField.text = settings.getIdleMinutes().toString()
        idleMinutesField.isEnabled = autoPauseCheckBox.isSelected

        recentTasksCountField.text = settings.getRecentTasksCount().toString()

        accumulateHierarchyCheckBox.isSelected = settings.isAccumulateHierarchyTime()
    }
}
