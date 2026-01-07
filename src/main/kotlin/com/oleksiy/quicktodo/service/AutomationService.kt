package com.oleksiy.quicktodo.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.oleksiy.quicktodo.model.AutomationState
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.util.ClaudeCodePluginChecker
import com.oleksiy.quicktodo.util.ClaudePromptBuilder
import com.oleksiy.quicktodo.util.TaskTextFormatter
import com.oleksiy.quicktodo.util.TerminalCommandRunner
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * Service managing the Claude automation workflow for QuickTodo tasks.
 *
 * Workflow:
 *   - Runs Claude Code with configurable execution mode per task:
 *     - Plan Mode: `claude --permission-mode plan "task"` (interactive review)
 *     - Accept Edits: `claude --allowedTools "..." "task"` (auto-accepts file operations)
 *     - Skip Permissions: `claude --dangerously-skip-permissions "task"`
 *   - User interacts with Claude in terminal
 *   - When Claude exits, task is marked as completed
 *   - Automatically starts the next task (or shows confirmation if auto-continue is off)
 *
 * The workflow can be paused, which will complete the current operation
 * but not start the next task.
 */
@Service(Service.Level.PROJECT)
class AutomationService(private val project: Project) : Disposable {

    /**
     * Listener interface for automation state changes.
     */
    interface AutomationListener {
        /** Called when the automation state changes */
        fun onStateChanged(state: AutomationState)

        /** Called when the current task changes */
        fun onCurrentTaskChanged(task: Task?)

        /** Called when an error occurs */
        fun onError(message: String)
    }

    private var state = AutomationState.IDLE
    private var currentTaskId: String? = null
    private var pauseRequested = false
    private val listeners = CopyOnWriteArrayList<AutomationListener>()
    private val signalMonitor = ClaudeSignalMonitor(project)

    // Configuration for the current session
    private var configuredTaskIds: List<String> = emptyList()
    private var currentTaskIndex: Int = 0
    private var autoContinue: Boolean = true

    private val taskService: TaskService
        get() = TaskService.getInstance(project)

    private val aiConfigService: AiConfigService
        get() = AiConfigService.getInstance(project)

    // Public state accessors

    /**
     * Gets the current automation state.
     */
    fun getState(): AutomationState = state

    /**
     * Gets the current task being processed.
     */
    fun getCurrentTask(): Task? = currentTaskId?.let { taskService.findTask(it) }

    /**
     * Checks if automation is currently running.
     */
    fun isRunning(): Boolean = state == AutomationState.WORKING

    /**
     * Checks if automation is paused.
     */
    fun isPaused(): Boolean = state == AutomationState.PAUSED

    /**
     * Checks if automation can be started (has required dependencies).
     */
    fun canStart(): Boolean {
        return ClaudeCodePluginChecker.isClaudeCodeInstalled() &&
                TerminalCommandRunner.isTerminalAvailable() &&
                state == AutomationState.IDLE
    }

    // State transitions

    /**
     * Starts the automation workflow from IDLE state.
     * Will find the first incomplete task and begin working.
     * @deprecated Use startWithConfig() instead for better control over task selection.
     */
    fun start() {
        if (state != AutomationState.IDLE) return

        // Find first incomplete task
        val nextTask = findNextIncompleteTask()
        if (nextTask == null) {
            notifyError("No incomplete tasks to process")
            return
        }

        pauseRequested = false
        configuredTaskIds = emptyList()
        currentTaskIndex = 0
        autoContinue = true
        currentTaskId = nextTask.id
        notifyCurrentTaskChanged()

        startWorking(nextTask)
    }

    /**
     * Starts the automation workflow with a specific list of task IDs.
     * Tasks will be processed in the order provided.
     *
     * @param taskIds Ordered list of task IDs to process
     * @param autoContinueMode If true, automatically proceed to next task. If false, show confirmation dialog.
     */
    fun startWithConfig(taskIds: List<String>, autoContinueMode: Boolean) {
        if (state != AutomationState.IDLE) return
        if (taskIds.isEmpty()) {
            notifyError("No tasks selected")
            return
        }

        // Store configuration
        configuredTaskIds = taskIds.toList()
        currentTaskIndex = 0
        autoContinue = autoContinueMode
        pauseRequested = false

        // Find and start first task
        val firstTask = findTaskByIndex(0)
        if (firstTask == null) {
            notifyError("First selected task not found")
            resetSession()
            return
        }

        currentTaskId = firstTask.id
        notifyCurrentTaskChanged()
        startWorking(firstTask)
    }

    private fun findTaskByIndex(index: Int): Task? {
        if (index < 0 || index >= configuredTaskIds.size) return null
        val taskId = configuredTaskIds[index]
        return taskService.findTask(taskId)
    }

    private fun resetSession() {
        configuredTaskIds = emptyList()
        currentTaskIndex = 0
        autoContinue = true
        aiConfigService.resetSession()
    }

    /**
     * Requests pause - automation will stop after current operation completes.
     */
    fun pause() {
        if (state != AutomationState.WORKING) return
        pauseRequested = true
    }

    /**
     * Resumes automation from PAUSED state.
     */
    fun resume() {
        if (state != AutomationState.PAUSED) return

        pauseRequested = false

        // Continue with next task
        val nextTask = findNextIncompleteTask()
        if (nextTask == null) {
            setState(AutomationState.IDLE)
            notifyError("No more incomplete tasks")
            return
        }

        currentTaskId = nextTask.id
        notifyCurrentTaskChanged()
        startWorking(nextTask)
    }

    /**
     * Stops automation immediately.
     * Note: This doesn't kill the running Claude process, just stops monitoring.
     */
    fun stop() {
        signalMonitor.stopMonitoring()
        signalMonitor.removeStopHook()
        pauseRequested = false
        currentTaskId = null
        setState(AutomationState.IDLE)
        notifyCurrentTaskChanged()
        resetSession()
    }

    // Private workflow methods

    private fun startWorking(task: Task) {
        setState(AutomationState.WORKING)

        // Build enhanced prompt with project context, code snippets, and session history
        val prompt = ClaudePromptBuilder.buildPrompt(
            project = project,
            task = task,
            completedTaskIds = aiConfigService.getCompletedTaskIds(),
            taskService = taskService,
            askMoreQuestions = aiConfigService.isAskMoreQuestions()
        )
        val escapedPrompt = TaskTextFormatter.escapeForShell(prompt)

        // Get execution mode and model for this specific task
        val executionMode = aiConfigService.getExecutionMode(task.id)
        val model = aiConfigService.getSelectedModel()
        val modelArg = "--model ${model.modelId}"
        val command = "claude $modelArg ${executionMode.commandArgs} \"$escapedPrompt\""

        // Start monitoring for completion
        signalMonitor.startMonitoring(
            onComplete = { onWorkComplete() },
            onTimeout = { onTimeout() }
        )

        // Execute the command in terminal
        if (!TerminalCommandRunner.executeCommand(project, command, "Claude Code")) {
            signalMonitor.stopMonitoring()
            setState(AutomationState.IDLE)
            notifyError("Failed to open terminal for Claude Code")
        }
    }

    private fun onWorkComplete() {
        SwingUtilities.invokeLater {
            // Mark current task as completed
            val taskId = currentTaskId
            if (taskId != null) {
                taskService.setTaskCompletion(taskId, true)
                aiConfigService.addCompletedTaskId(taskId)
            }

            if (pauseRequested) {
                setState(AutomationState.PAUSED)
                pauseRequested = false
                currentTaskId = null
                notifyCurrentTaskChanged()
                aiConfigService.pauseSession()
                return@invokeLater
            }

            // Find next task - use configured list if available, otherwise find next incomplete
            val nextTask = findNextTask()
            if (nextTask == null) {
                // All tasks completed
                setState(AutomationState.IDLE)
                currentTaskId = null
                notifyCurrentTaskChanged()
                signalMonitor.removeStopHook()
                aiConfigService.completeSession()
                return@invokeLater
            }

            // Check if we need to show confirmation dialog (when auto-continue is off)
            if (!autoContinue) {
                showContinueConfirmation(nextTask)
            } else {
                proceedToNextTask(nextTask)
            }
        }
    }

    private fun findNextTask(): Task? {
        // If we have a configured task list, use it
        if (configuredTaskIds.isNotEmpty()) {
            currentTaskIndex++
            aiConfigService.setCurrentIndex(currentTaskIndex)
            return findTaskByIndex(currentTaskIndex)
        }

        // Otherwise, fall back to finding next incomplete task
        return findNextIncompleteTask()
    }

    private fun showContinueConfirmation(nextTask: Task) {
        val remainingCount = if (configuredTaskIds.isNotEmpty()) {
            configuredTaskIds.size - currentTaskIndex
        } else {
            countRemainingTasks()
        }

        val message = """
            |Task completed: ${getCurrentTask()?.text ?: "Unknown"}
            |
            |Next task: ${nextTask.text}
            |Remaining tasks: $remainingCount
            |
            |Continue to next task?
        """.trimMargin()

        val result = com.intellij.openapi.ui.Messages.showYesNoCancelDialog(
            project,
            message,
            "Task Completed",
            "Continue",
            "Skip & Continue",
            "Stop",
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )

        when (result) {
            com.intellij.openapi.ui.Messages.YES -> {
                // Continue with this task
                proceedToNextTask(nextTask)
            }
            com.intellij.openapi.ui.Messages.NO -> {
                // Skip this task and find next one
                skipAndContinue()
            }
            else -> {
                // Stop automation
                stop()
            }
        }
    }

    private fun proceedToNextTask(task: Task) {
        currentTaskId = task.id
        notifyCurrentTaskChanged()
        startWorking(task)
    }

    private fun skipAndContinue() {
        val nextTask = findNextTask()
        if (nextTask == null) {
            setState(AutomationState.IDLE)
            currentTaskId = null
            notifyCurrentTaskChanged()
            signalMonitor.removeStopHook()
            aiConfigService.completeSession()
        } else if (!autoContinue) {
            showContinueConfirmation(nextTask)
        } else {
            proceedToNextTask(nextTask)
        }
    }

    private fun countRemainingTasks(): Int {
        var count = 0
        fun countIncomplete(tasks: List<Task>) {
            for (task in tasks) {
                if (!task.isCompleted) count++
                countIncomplete(task.subtasks)
            }
        }
        countIncomplete(taskService.getTasks())
        return count
    }

    private fun onTimeout() {
        SwingUtilities.invokeLater {
            signalMonitor.stopMonitoring()
            setState(AutomationState.PAUSED)
            notifyError("Timeout waiting for Claude to complete. Automation paused.")
        }
    }

    /**
     * Finds the next incomplete task using depth-first traversal.
     */
    private fun findNextIncompleteTask(): Task? {
        fun findIncomplete(tasks: List<Task>): Task? {
            for (task in tasks) {
                if (!task.isCompleted) return task
                // Check subtasks even if parent is not completed
                val subtaskResult = findIncomplete(task.subtasks)
                if (subtaskResult != null) return subtaskResult
            }
            return null
        }
        return findIncomplete(taskService.getTasks())
    }

    // State and listener management

    private fun setState(newState: AutomationState) {
        if (state != newState) {
            state = newState
            notifyStateChanged()
        }
    }

    private fun notifyStateChanged() {
        listeners.forEach { it.onStateChanged(state) }
    }

    private fun notifyCurrentTaskChanged() {
        val task = getCurrentTask()
        listeners.forEach { it.onCurrentTaskChanged(task) }
    }

    private fun notifyError(message: String) {
        listeners.forEach { it.onError(message) }
    }

    /**
     * Adds a listener for automation events.
     */
    fun addListener(listener: AutomationListener) {
        listeners.add(listener)
    }

    /**
     * Removes a listener.
     */
    fun removeListener(listener: AutomationListener) {
        listeners.remove(listener)
    }

    override fun dispose() {
        signalMonitor.stopMonitoring()
        signalMonitor.removeStopHook()
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): AutomationService {
            return project.getService(AutomationService::class.java)
        }
    }
}
