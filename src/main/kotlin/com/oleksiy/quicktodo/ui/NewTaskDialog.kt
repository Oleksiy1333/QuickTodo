package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.CodeLocation
import com.oleksiy.quicktodo.model.Priority
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class NewTaskDialog(
    private val project: Project,
    dialogTitle: String = "New Task",
    initialText: String = "",
    initialPriority: Priority = Priority.NONE,
    private val initialLocation: CodeLocation? = null
) : DialogWrapper(project) {

    private val nameField = JBTextField(initialText)
    private val priorityComboBox = ComboBox(Priority.entries.toTypedArray())

    // Location components
    private val includeLocationCheckbox = JBCheckBox("Include current cursor position")
    private val locationLinkLabel = JBLabel()
    private val clearLocationButton = JButton("X")
    private val locationPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

    // Current location state
    private var currentLocation: CodeLocation? = initialLocation?.copy()
    private var isUpdatingCheckbox = false

    init {
        title = dialogTitle
        priorityComboBox.selectedItem = initialPriority
        priorityComboBox.renderer = PriorityListCellRenderer()

        setupLocationComponents()
        init()
    }

    private fun setupLocationComponents() {
        // Style the link label
        locationLinkLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        locationLinkLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        locationLinkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                currentLocation?.let { location ->
                    if (CodeLocationUtil.navigateToLocation(project, location)) {
                        close(OK_EXIT_CODE)
                    }
                }
            }
        })

        // Style the clear button
        clearLocationButton.preferredSize = Dimension(24, 24)
        clearLocationButton.margin = JBUI.emptyInsets()
        clearLocationButton.toolTipText = "Remove attached location"
        clearLocationButton.addActionListener {
            clearLocation()
        }

        // Build location display panel
        locationPanel.add(locationLinkLabel)
        locationPanel.add(Box.createHorizontalStrut(4))
        locationPanel.add(clearLocationButton)

        // Checkbox behavior - use SwingUtilities.invokeLater to avoid modifying state during event
        includeLocationCheckbox.addItemListener { e ->
            if (isUpdatingCheckbox) return@addItemListener
            javax.swing.SwingUtilities.invokeLater {
                if (e.stateChange == java.awt.event.ItemEvent.SELECTED) {
                    captureLocation()
                } else if (e.stateChange == java.awt.event.ItemEvent.DESELECTED) {
                    currentLocation = null
                    updateLocationDisplay()
                }
            }
        }

        // Initialize UI state based on initial location
        updateLocationDisplay()
    }

    private fun captureLocation() {
        val captured = CodeLocationUtil.captureCurrentLocation(project)
        if (captured != null) {
            currentLocation = captured
            updateLocationDisplay()
        } else {
            // No editor open - uncheck and show message
            currentLocation = null
            setCheckboxSelected(false)
            updateLocationDisplay()
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "No file is currently open in the editor. Please open a file first.",
                "Cannot Capture Location"
            )
        }
    }

    private fun clearLocation() {
        currentLocation = null
        setCheckboxSelected(false)
        updateLocationDisplay()
    }

    private fun setCheckboxSelected(selected: Boolean) {
        isUpdatingCheckbox = true
        try {
            includeLocationCheckbox.isSelected = selected
        } finally {
            isUpdatingCheckbox = false
        }
    }

    private fun updateLocationDisplay() {
        val hasLocation = currentLocation?.isValid() == true

        locationPanel.isVisible = hasLocation

        if (hasLocation) {
            locationLinkLabel.text = "<html><u>${currentLocation!!.toDisplayString()}</u></html>"
            setCheckboxSelected(true)
        } else {
            locationLinkLabel.text = ""
        }

        // Revalidate to update layout
        locationPanel.revalidate()
        locationPanel.repaint()
    }

    override fun createCenterPanel(): JComponent {
        nameField.preferredSize = Dimension(300, nameField.preferredSize.height)

        // Create location row with checkbox and link/clear
        val locationRowPanel = JPanel(BorderLayout()).apply {
            add(includeLocationCheckbox, BorderLayout.WEST)
            add(locationPanel, BorderLayout.CENTER)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Priority:", priorityComboBox)
            .addComponent(locationRowPanel)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    fun getTaskText(): String = nameField.text.trim()
    fun getSelectedPriority(): Priority = priorityComboBox.selectedItem as Priority
    fun getCodeLocation(): CodeLocation? = currentLocation?.takeIf { it.isValid() }

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
