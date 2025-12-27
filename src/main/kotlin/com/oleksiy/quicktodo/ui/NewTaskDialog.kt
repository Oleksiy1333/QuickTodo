package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.Priority
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton

class NewTaskDialog(project: Project, dialogTitle: String = "New Task") : DialogWrapper(project) {
    private val nameField = JBTextField()
    private var selectedPriority = Priority.NONE
    private val priorityButtons = mutableMapOf<Priority, JToggleButton>()

    init {
        title = dialogTitle
        init()
    }

    override fun createCenterPanel(): JComponent {
        nameField.preferredSize = Dimension(300, nameField.preferredSize.height)

        val priorityPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))

        Priority.entries.forEach { priority ->
            val button = JToggleButton(priority.displayName, QuickTodoIcons.getIconForPriority(priority))
            button.isSelected = priority == Priority.NONE
            button.isFocusable = true
            button.isFocusPainted = true
            button.addActionListener {
                selectedPriority = priority
                // Deselect other buttons
                priorityButtons.forEach { (p, b) ->
                    b.isSelected = (p == priority)
                }
            }
            button.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent?) {
                    selectedPriority = priority
                    priorityButtons.forEach { (p, b) ->
                        b.isSelected = (p == priority)
                    }
                }
            })
            priorityButtons[priority] = button
            priorityPanel.add(button)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Priority:", priorityPanel)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    fun getTaskText(): String = nameField.text.trim()
    fun getSelectedPriority(): Priority = selectedPriority
}
