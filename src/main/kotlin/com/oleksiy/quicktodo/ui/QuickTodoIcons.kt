package com.oleksiy.quicktodo.ui

import com.intellij.icons.AllIcons
import com.oleksiy.quicktodo.model.Priority
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object QuickTodoIcons {
    @JvmField
    val FlagHigh: Icon = IconLoader.getIcon("/icons/flagHigh.svg", javaClass)

    @JvmField
    val FlagMedium: Icon = IconLoader.getIcon("/icons/flagMedium.svg", javaClass)

    @JvmField
    val FlagLow: Icon = IconLoader.getIcon("/icons/flagLow.svg", javaClass)

    @JvmField
    val Focus: Icon = IconLoader.getIcon("/icons/focus.svg", javaClass)

    @JvmField
    val TaskMarker: Icon = IconLoader.getIcon("/icons/checklist.svg", javaClass)

    @JvmField
    val AddSubtask: Icon = IconLoader.getIcon("/icons/addSubtask.svg", javaClass)

    @JvmField
    val Claude: Icon = IconLoader.getIcon("/icons/claude.svg", javaClass)

    // Automation icons (using IntelliJ built-in icons)
    @JvmField
    val AutomationRun: Icon = AllIcons.Actions.Execute

    @JvmField
    val AutomationPause: Icon = AllIcons.Actions.Pause

    @JvmField
    val AutomationStop: Icon = AllIcons.Actions.Suspend

    @JvmField
    val AutomationResume: Icon = AllIcons.Actions.Resume

    fun getIconForPriority(priority: Priority): Icon? {
        return when (priority) {
            Priority.HIGH -> FlagHigh
            Priority.MEDIUM -> FlagMedium
            Priority.LOW -> FlagLow
            Priority.NONE -> null
        }
    }
}
