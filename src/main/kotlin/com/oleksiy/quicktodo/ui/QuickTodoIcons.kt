package com.oleksiy.quicktodo.ui

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

    fun getIconForPriority(priority: Priority): Icon? {
        return when (priority) {
            Priority.HIGH -> FlagHigh
            Priority.MEDIUM -> FlagMedium
            Priority.LOW -> FlagLow
            Priority.NONE -> null
        }
    }
}
