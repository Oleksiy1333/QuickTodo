package com.oleksiy.quicktodo.util

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Utility for running commands in the IDE's terminal.
 */
object TerminalCommandRunner {

    /**
     * Checks if terminal functionality is available.
     */
    fun isTerminalAvailable(): Boolean {
        return try {
            Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /**
     * Opens a new terminal tab and executes the given command.
     * @param project The current project
     * @param command The command to execute
     * @param tabName Name for the terminal tab
     * @return true if command was executed, false if terminal is unavailable
     */
    fun executeCommand(project: Project, command: String, tabName: String = "Claude Code"): Boolean {
        if (!isTerminalAvailable()) {
            return false
        }

        return try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val widget = terminalManager.createLocalShellWidget(project.basePath ?: ".", tabName)
            widget.executeCommand(command)
            true
        } catch (e: Exception) {
            false
        }
    }
}
