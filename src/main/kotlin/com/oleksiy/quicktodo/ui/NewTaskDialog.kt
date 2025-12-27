package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Priority
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList

class NewTaskDialog(
    project: Project,
    dialogTitle: String = "New Task",
    initialText: String = "",
    initialPriority: Priority = Priority.NONE
) : DialogWrapper(project) {
    private val nameField = JBTextField(initialText)
    private val priorityComboBox = ComboBox(Priority.entries.toTypedArray())

    init {
        title = dialogTitle
        priorityComboBox.selectedItem = initialPriority
        priorityComboBox.renderer = PriorityListCellRenderer()
        init()
    }

    override fun createCenterPanel(): JComponent {
        nameField.preferredSize = Dimension(300, nameField.preferredSize.height)

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Priority:", priorityComboBox)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    fun getTaskText(): String = nameField.text.trim()
    fun getSelectedPriority(): Priority = priorityComboBox.selectedItem as Priority

    private inner class PriorityListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val priority = value as? Priority ?: return this
            text = priority.displayName
            icon = QuickTodoIcons.getIconForPriority(priority)
            return this
        }
    }
}
