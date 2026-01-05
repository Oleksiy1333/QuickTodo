package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.CodeLocation
import com.oleksiy.quicktodo.model.Priority
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
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
    private val initialLocation: CodeLocation? = null,
    initialDescription: String = ""
) : DialogWrapper(project) {

    private val nameField = JBTextField(initialText)
    private val descriptionTextArea = JBTextArea(initialDescription, 3, 30).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val descriptionScrollPane = JBScrollPane(descriptionTextArea)
    private val priorityComboBox = ComboBox(Priority.entries.toTypedArray())

    // Location components
    private val linkLocationButton = JButton("Link Location", AllIcons.General.Add)
    private val locationLinkLabel = JBLabel()
    private val clearLocationButton = JButton("Clear")

    // Card layout to switch between "link" button and "location display"
    private val locationCardPanel = JPanel(CardLayout())
    private val locationDisplayPanel = JPanel()

    // Current location state
    private var currentLocation: CodeLocation? = initialLocation?.copy()

    companion object {
        private const val CARD_LINK_BUTTON = "linkButton"
        private const val CARD_LOCATION_DISPLAY = "locationDisplay"
    }

    init {
        title = dialogTitle
        priorityComboBox.selectedItem = initialPriority
        priorityComboBox.renderer = PriorityListCellRenderer()

        setupLocationComponents()
        init()
    }

    private fun setupLocationComponents() {
        // Setup "Link Location" button
        linkLocationButton.addActionListener {
            captureLocation()
        }

        // Style the location link label
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

        // Setup clear button
        clearLocationButton.addActionListener {
            clearLocation()
        }

        // Build location display panel (shown when location is linked)
        locationDisplayPanel.layout = BoxLayout(locationDisplayPanel, BoxLayout.X_AXIS)
        locationDisplayPanel.add(locationLinkLabel)
        locationDisplayPanel.add(Box.createHorizontalStrut(12))
        locationDisplayPanel.add(clearLocationButton)
        locationDisplayPanel.add(Box.createHorizontalGlue())

        // Build card panel for switching between states
        val linkButtonPanel = JPanel(BorderLayout()).apply {
            add(linkLocationButton, BorderLayout.WEST)
        }

        locationCardPanel.add(linkButtonPanel, CARD_LINK_BUTTON)
        locationCardPanel.add(locationDisplayPanel, CARD_LOCATION_DISPLAY)

        // Set consistent height to prevent layout shift
        val height = maxOf(linkLocationButton.preferredSize.height, clearLocationButton.preferredSize.height)
        locationCardPanel.preferredSize = Dimension(350, height)
        locationCardPanel.minimumSize = Dimension(0, height)

        // Initialize UI state
        updateLocationDisplay()
    }

    private fun captureLocation() {
        val captured = CodeLocationUtil.captureCurrentLocation(project)
        if (captured != null) {
            currentLocation = captured
            updateLocationDisplay()
        } else {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "No file is currently open in the editor. Please open a file first.",
                "Cannot Capture Location"
            )
        }
    }

    private fun clearLocation() {
        currentLocation = null
        updateLocationDisplay()
    }

    private fun updateLocationDisplay() {
        val cardLayout = locationCardPanel.layout as CardLayout
        val hasLocation = currentLocation?.isValid() == true

        if (hasLocation) {
            // Show location display
            val loc = currentLocation!!
            val displayText = if (loc.hasSelection() && loc.endLine > loc.line) {
                "${loc.relativePath}:${loc.line + 1}-${loc.endLine + 1}"
            } else {
                "${loc.relativePath}:${loc.line + 1}"
            }
            locationLinkLabel.text = "<html><u>$displayText</u></html>"
            cardLayout.show(locationCardPanel, CARD_LOCATION_DISPLAY)
        } else {
            locationLinkLabel.text = ""
            cardLayout.show(locationCardPanel, CARD_LINK_BUTTON)
        }
    }

    override fun createCenterPanel(): JComponent {
        nameField.preferredSize = Dimension(350, nameField.preferredSize.height)
        descriptionScrollPane.preferredSize = Dimension(350, 80)

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Description:", descriptionScrollPane)
            .addLabeledComponent("Priority:", priorityComboBox)
            .addVerticalGap(8)
            .addLabeledComponent("Location:", locationCardPanel)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    fun getTaskText(): String = nameField.text.trim()
    fun getDescription(): String = descriptionTextArea.text.trim()
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
