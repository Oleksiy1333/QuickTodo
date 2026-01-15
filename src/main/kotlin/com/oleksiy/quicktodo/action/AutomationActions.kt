package com.oleksiy.quicktodo.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.oleksiy.quicktodo.model.AutomationState
import com.oleksiy.quicktodo.service.AiConfigService
import com.oleksiy.quicktodo.service.AutomationService
import com.oleksiy.quicktodo.ui.QuickTodoIcons
import com.oleksiy.quicktodo.ui.ai.TaskSelectionDialog
import com.oleksiy.quicktodo.settings.QuickTodoSettings
import com.oleksiy.quicktodo.util.ClaudeCodePluginChecker
import com.oleksiy.quicktodo.util.TerminalCommandRunner

/**
 * Action to start the Claude automation workflow.
 * Shows task selection dialog, then processes selected tasks with configurable execution modes.
 */
class StartAutomationAction(
    private val automationServiceProvider: () -> AutomationService?,
    private val projectProvider: () -> com.intellij.openapi.project.Project?
) : AnAction(
    "Build with Claude",
    "Start building tasks with Claude Code",
    QuickTodoIcons.Claude
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = projectProvider() ?: return
        val automationService = automationServiceProvider() ?: return

        // Show task selection dialog
        val dialog = TaskSelectionDialog(project)
        if (dialog.showAndGet()) {
            val selectedTaskIds = dialog.getSelectedTaskIds()
            val autoContinue = dialog.isAutoContinue()
            val taskConfigs = dialog.getTaskConfigs()

            if (selectedTaskIds.isNotEmpty()) {
                // Save config and task execution modes
                val aiConfig = AiConfigService.getInstance(project)
                aiConfig.startSession(selectedTaskIds, autoContinue)
                aiConfig.setTaskConfigs(taskConfigs)
                aiConfig.setAskMoreQuestions(dialog.isAskMoreQuestions())
                aiConfig.setSelectedModel(dialog.getSelectedModel())

                automationService.startWithConfig(selectedTaskIds, autoContinue)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val service = automationServiceProvider()
        val canStart = service?.canStart() == true
        val isVisible = QuickTodoSettings.getInstance().isClaudeIntegrationEnabled() &&
                ClaudeCodePluginChecker.isClaudeCodeInstalled() &&
                TerminalCommandRunner.isTerminalAvailable()

        e.presentation.isEnabled = canStart
        e.presentation.isVisible = isVisible
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/**
 * Action to pause the Claude automation workflow.
 * The current Claude operation will complete, but no new task will be started.
 */
class PauseAutomationAction(
    private val automationServiceProvider: () -> AutomationService?
) : AnAction(
    "Pause Automation",
    "Pause automation after current operation completes",
    QuickTodoIcons.AutomationPause
) {
    override fun actionPerformed(e: AnActionEvent) {
        automationServiceProvider()?.pause()
    }

    override fun update(e: AnActionEvent) {
        val service = automationServiceProvider()
        val state = service?.getState()
        val canPause = state == AutomationState.WORKING

        e.presentation.isEnabled = canPause
        e.presentation.isVisible = service?.isRunning() == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/**
 * Action to resume the Claude automation workflow from paused state.
 */
class ResumeAutomationAction(
    private val automationServiceProvider: () -> AutomationService?
) : AnAction(
    "Resume Automation",
    "Resume automated planning and implementation",
    QuickTodoIcons.AutomationResume
) {
    override fun actionPerformed(e: AnActionEvent) {
        automationServiceProvider()?.resume()
    }

    override fun update(e: AnActionEvent) {
        val service = automationServiceProvider()
        val canResume = service?.isPaused() == true

        e.presentation.isEnabled = canResume
        e.presentation.isVisible = canResume
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/**
 * Action to stop the Claude automation workflow.
 * Note: This stops monitoring but doesn't kill the running Claude process.
 */
class StopAutomationAction(
    private val automationServiceProvider: () -> AutomationService?
) : AnAction(
    "Stop Automation",
    "Stop the automation workflow",
    QuickTodoIcons.AutomationStop
) {
    override fun actionPerformed(e: AnActionEvent) {
        automationServiceProvider()?.stop()
    }

    override fun update(e: AnActionEvent) {
        val service = automationServiceProvider()
        val isRunning = service?.isRunning() == true || service?.isPaused() == true

        e.presentation.isEnabled = isRunning
        e.presentation.isVisible = isRunning
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
