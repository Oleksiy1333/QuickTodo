package com.oleksiy.quicktodo.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComponent

class EditTimeDialog(
    project: Project,
    private val taskName: String,
    initialTimeMs: Long
) : DialogWrapper(project) {

    private val hoursField = JBTextField(3)
    private val minutesField = JBTextField(3)
    private val secondsField = JBTextField(3)

    init {
        title = "Edit Time"

        // Convert milliseconds to hours, minutes, seconds
        val totalSeconds = initialTimeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        hoursField.text = hours.toString()
        minutesField.text = minutes.toString()
        secondsField.text = seconds.toString()

        init()
    }

    override fun createCenterPanel(): JComponent {
        val timePanel = javax.swing.JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            add(hoursField)
            add(JBLabel(" h "))
            add(minutesField)
            add(JBLabel(" m "))
            add(secondsField)
            add(JBLabel(" s"))
        }

        return FormBuilder.createFormBuilder()
            .addComponent(JBLabel("Edit own time for: $taskName"))
            .addVerticalGap(8)
            .addLabeledComponent("Time:", timePanel)
            .panel.apply {
                preferredSize = Dimension(350, preferredSize.height)
            }
    }

    override fun getPreferredFocusedComponent(): JComponent = hoursField

    /**
     * Returns the time in milliseconds, or null if input is invalid
     */
    fun getTimeMs(): Long? {
        return try {
            val hours = hoursField.text.toLongOrNull() ?: 0
            val minutes = minutesField.text.toLongOrNull() ?: 0
            val seconds = secondsField.text.toLongOrNull() ?: 0

            if (hours < 0 || minutes < 0 || seconds < 0) {
                return null
            }
            if (minutes >= 60 || seconds >= 60) {
                return null
            }

            (hours * 3600 + minutes * 60 + seconds) * 1000
        } catch (e: NumberFormatException) {
            null
        }
    }

    override fun doOKAction() {
        if (getTimeMs() == null) {
            setErrorText("Invalid time format")
            return
        }
        super.doOKAction()
    }
}
