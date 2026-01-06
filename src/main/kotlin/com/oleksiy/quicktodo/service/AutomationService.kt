package com.oleksiy.quicktodo.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.oleksiy.quicktodo.model.AutomationState
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.util.ClaudeCodePluginChecker
import com.oleksiy.quicktodo.util.TaskTextFormatter
import com.oleksiy.quicktodo.util.TerminalCommandRunner
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * Service managing the Claude automation workflow for QuickTodo tasks.
 *
 * Single-phase workflow:
 *   - Runs `claude --permission-mode plan "task"` for each incomplete task
 *   - User interacts with Claude in terminal (reviews plan, approves changes)
 *   - When Claude exits, task is marked as completed
 *   - Automatically starts the next incomplete task
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

    private val taskService: TaskService
        get() = TaskService.getInstance(project)

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
        currentTaskId = nextTask.id
        notifyCurrentTaskChanged()

        startWorking(nextTask)
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
    }

    // Private workflow methods

    private fun startWorking(task: Task) {
        setState(AutomationState.WORKING)

        val taskText = TaskTextFormatter.escapeForShell(TaskTextFormatter.formatTaskWithSubtasks(task))

        // Use plan mode - user reviews and approves changes interactively
        val command = "claude --permission-mode plan \"$taskText\""

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
            }

            if (pauseRequested) {
                setState(AutomationState.PAUSED)
                pauseRequested = false
                currentTaskId = null
                notifyCurrentTaskChanged()
                return@invokeLater
            }

            // Find next task
            val nextTask = findNextIncompleteTask()
            if (nextTask == null) {
                // All tasks completed
                setState(AutomationState.IDLE)
                currentTaskId = null
                notifyCurrentTaskChanged()
                signalMonitor.removeStopHook()
                return@invokeLater
            }

            // Continue with next task
            currentTaskId = nextTask.id
            notifyCurrentTaskChanged()
            startWorking(nextTask)
        }
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
