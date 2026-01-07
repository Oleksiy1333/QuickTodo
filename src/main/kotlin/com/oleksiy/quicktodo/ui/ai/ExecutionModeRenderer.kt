package com.oleksiy.quicktodo.ui.ai

import com.intellij.icons.AllIcons
import com.oleksiy.quicktodo.model.ClaudeExecutionMode
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * Custom renderer for ClaudeExecutionMode in ComboBox.
 * Shows icon + display name with mode description as tooltip.
 */
class ExecutionModeRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

        val mode = value as? ClaudeExecutionMode ?: return this
        text = mode.displayName
        toolTipText = mode.description
        icon = when (mode) {
            ClaudeExecutionMode.DEFAULT -> AllIcons.Actions.SetDefault
            ClaudeExecutionMode.PLAN -> AllIcons.Actions.Preview
            ClaudeExecutionMode.ACCEPT_EDITS -> AllIcons.Actions.Execute
            ClaudeExecutionMode.SKIP_PERMISSIONS -> AllIcons.General.Warning
        }
        return this
    }
}
