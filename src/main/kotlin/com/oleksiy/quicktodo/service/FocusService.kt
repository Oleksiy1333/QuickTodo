package com.oleksiy.quicktodo.service

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.oleksiy.quicktodo.model.TimerState
import com.oleksiy.quicktodo.settings.QuickTodoSettings
import java.awt.AWTEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Timer

@Service(Service.Level.PROJECT)
class FocusService(private val project: Project) : Disposable {

    interface FocusChangeListener {
        fun onFocusChanged(focusedTaskId: String?)
        fun onTimerTick()
    }

    private var focusedTaskId: String? = null
    private val timerStates: MutableMap<String, TimerState> = mutableMapOf()
    private var updateTimer: Timer? = null
    private val listeners = CopyOnWriteArrayList<FocusChangeListener>()

    // Autopause state
    private var lastActivityTime: Long = System.currentTimeMillis()
    private var wasAutoPaused: Boolean = false
    private val activityListener: IdeEventQueue.EventDispatcher

    private val taskService: TaskService
        get() = TaskService.getInstance(project)

    init {
        // Listen to IDE activity for autopause
        activityListener = IdeEventQueue.EventDispatcher { event ->
            if (event is KeyEvent || event is MouseEvent) {
                onUserActivity()
            }
            false
        }
        IdeEventQueue.getInstance().addDispatcher(activityListener, this)
    }

    fun getFocusedTaskId(): String? = focusedTaskId

    fun isFocused(taskId: String): Boolean = focusedTaskId == taskId

    fun getTimerState(taskId: String): TimerState = timerStates[taskId] ?: TimerState.STOPPED

    fun setFocus(taskId: String) {
        val task = taskService.findTask(taskId) ?: return
        if (task.isCompleted) return

        val previousFocusId = focusedTaskId
        if (previousFocusId != null && previousFocusId != taskId) {
            stopTimerAndAccumulate(previousFocusId)
            pauseParentTimers(previousFocusId)
        }

        focusedTaskId = taskId
        task.lastModified = System.currentTimeMillis()
        startTimer(taskId)
        startParentTimers(taskId)
        startSwingTimer()
        notifyFocusChanged()
    }

    fun removeFocus() {
        val taskId = focusedTaskId ?: return

        stopTimerAndAccumulate(taskId)
        pauseParentTimers(taskId)

        focusedTaskId = null
        stopSwingTimer()
        notifyFocusChanged()
    }

    fun pauseFocus() {
        val taskId = focusedTaskId ?: return
        if (timerStates[taskId] != TimerState.RUNNING) return

        pauseTimer(taskId)
        pauseParentTimers(taskId)
        stopSwingTimer()
        notifyFocusChanged()
    }

    fun resumeFocus() {
        val taskId = focusedTaskId ?: return
        if (timerStates[taskId] != TimerState.PAUSED) return

        startTimer(taskId)
        startParentTimers(taskId)
        startSwingTimer()
        notifyFocusChanged()
    }

    fun isPaused(): Boolean = focusedTaskId?.let { timerStates[it] == TimerState.PAUSED } ?: false

    fun isRunning(): Boolean = focusedTaskId?.let { timerStates[it] == TimerState.RUNNING } ?: false

    fun onTaskCompleted(taskId: String) {
        val currentFocusId = focusedTaskId ?: return

        if (currentFocusId == taskId) {
            stopTimerAndAccumulate(taskId)
            pauseParentTimers(taskId)
            focusedTaskId = null
            stopSwingTimerIfNoRunning()
            notifyFocusChanged()
        } else if (taskService.isAncestorOf(taskId, currentFocusId)) {
            stopAllTimersInSubtree(taskId)
            focusedTaskId = null
            stopSwingTimer()
            notifyFocusChanged()
        }
    }

    fun onTaskDeleted(taskId: String) {
        val currentFocusId = focusedTaskId ?: return

        if (currentFocusId == taskId || taskService.isAncestorOf(taskId, currentFocusId)) {
            timerStates.remove(taskId)
            focusedTaskId = null
            stopSwingTimer()
            notifyFocusChanged()
        }
    }

    /**
     * Gets the total elapsed time for a task including hierarchy accumulation.
     * This shows ownTime + accumulatedHierarchyTime + any currently running timers.
     */
    fun getElapsedTime(taskId: String): Long {
        val task = taskService.findTask(taskId) ?: return 0

        // Start with own time
        var totalTime = task.ownTimeSpentMs

        // Add currently running own timer if applicable
        val ownFocusStart = task.lastFocusStartedAt
        if (ownFocusStart != null && timerStates[taskId] == TimerState.RUNNING) {
            totalTime += (System.currentTimeMillis() - ownFocusStart)
        }

        // Add accumulated hierarchy time only if setting is enabled
        if (QuickTodoSettings.getInstance().isAccumulateHierarchyTime()) {
            // Add accumulated hierarchy time (already calculated)
            totalTime += task.accumulatedHierarchyTimeMs

            // Add currently running accumulated timer if applicable
            val accumulatedFocusStart = task.lastAccumulatedFocusStartedAt
            if (accumulatedFocusStart != null) {
                totalTime += (System.currentTimeMillis() - accumulatedFocusStart)
            }
        }

        return totalTime
    }

    fun getFormattedTime(taskId: String): String {
        val task = taskService.findTask(taskId) ?: return ""

        // Calculate own time (with running timer if applicable)
        var ownTime = task.ownTimeSpentMs
        val ownFocusStart = task.lastFocusStartedAt
        if (ownFocusStart != null && timerStates[taskId] == TimerState.RUNNING) {
            ownTime += (System.currentTimeMillis() - ownFocusStart)
        }

        // Calculate accumulated time (with running timer if applicable) only if setting is enabled
        var accumulatedTime = 0L
        if (QuickTodoSettings.getInstance().isAccumulateHierarchyTime()) {
            accumulatedTime = task.accumulatedHierarchyTimeMs
            val accumulatedFocusStart = task.lastAccumulatedFocusStartedAt
            if (accumulatedFocusStart != null) {
                accumulatedTime += (System.currentTimeMillis() - accumulatedFocusStart)
            }
        }

        // Format display based on own time and accumulated time
        return when {
            accumulatedTime > 0 && ownTime > 0 -> {
                // Show own time with total in parenthesis: "5m (15m)"
                "${formatTime(ownTime)} (${formatTime(ownTime + accumulatedTime)})"
            }
            accumulatedTime > 0 && ownTime == 0L -> {
                // Only show total in parenthesis: "(10m)"
                "(${formatTime(accumulatedTime)})"
            }
            ownTime > 0 -> {
                // Show own time only
                formatTime(ownTime)
            }
            else -> {
                // No time to display
                ""
            }
        }
    }

    fun hasAccumulatedTime(taskId: String): Boolean {
        val task = taskService.findTask(taskId) ?: return false

        val hasOwnTime = task.ownTimeSpentMs > 0 || timerStates[taskId] == TimerState.RUNNING

        // If hierarchy accumulation is enabled, also check accumulated time
        val hasHierarchyTime = if (QuickTodoSettings.getInstance().isAccumulateHierarchyTime()) {
            task.accumulatedHierarchyTimeMs > 0 || task.lastAccumulatedFocusStartedAt != null
        } else {
            false
        }

        return hasOwnTime || hasHierarchyTime
    }

    private fun formatTime(totalMs: Long): String {
        val totalSeconds = totalMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private fun startTimer(taskId: String) {
        val task = taskService.findTask(taskId) ?: return
        task.lastFocusStartedAt = System.currentTimeMillis()
        timerStates[taskId] = TimerState.RUNNING
    }

    private fun startAccumulatedTimer(taskId: String) {
        val task = taskService.findTask(taskId) ?: return
        task.lastAccumulatedFocusStartedAt = System.currentTimeMillis()
    }

    private fun stopTimerAndAccumulate(taskId: String) {
        val task = taskService.findTask(taskId) ?: return

        // Stop own-time timer
        val focusStart = task.lastFocusStartedAt
        if (focusStart != null) {
            val elapsed = System.currentTimeMillis() - focusStart
            task.ownTimeSpentMs += elapsed
            task.lastFocusStartedAt = null
            taskService.addFocusTime(elapsed)
        }

        timerStates[taskId] = TimerState.STOPPED

        // Recalculate hierarchy time for the entire tree
        taskService.recalculateHierarchyTime()
    }

    private fun stopAccumulatedTimer(taskId: String) {
        val task = taskService.findTask(taskId) ?: return
        task.lastAccumulatedFocusStartedAt = null
    }

    private fun pauseTimer(taskId: String) {
        val task = taskService.findTask(taskId) ?: return

        // Pause own-time timer
        val focusStart = task.lastFocusStartedAt
        if (focusStart != null) {
            val elapsed = System.currentTimeMillis() - focusStart
            task.ownTimeSpentMs += elapsed
            task.lastFocusStartedAt = null
            taskService.addFocusTime(elapsed)
        }

        timerStates[taskId] = TimerState.PAUSED

        // Recalculate hierarchy time for the entire tree
        taskService.recalculateHierarchyTime()
    }

    private fun pauseAccumulatedTimer(taskId: String) {
        val task = taskService.findTask(taskId) ?: return
        task.lastAccumulatedFocusStartedAt = null
    }

    private fun startParentTimers(taskId: String) {
        // Only accumulate hierarchy time if setting is enabled
        if (!QuickTodoSettings.getInstance().isAccumulateHierarchyTime()) {
            return
        }

        var currentId = taskId
        while (true) {
            val parentId = taskService.findParentId(currentId) ?: break
            startAccumulatedTimer(parentId)
            currentId = parentId
        }
    }

    private fun pauseParentTimers(taskId: String) {
        // Always pause parent timers unconditionally to handle setting changes
        var currentId = taskId
        while (true) {
            val parentId = taskService.findParentId(currentId) ?: break
            pauseAccumulatedTimer(parentId)
            currentId = parentId
        }
    }

    private fun stopAllTimersInSubtree(taskId: String) {
        stopTimerAndAccumulate(taskId)
        val task = taskService.findTask(taskId) ?: return
        for (subtask in task.subtasks) {
            stopAllTimersInSubtree(subtask.id)
        }
    }

    private fun startSwingTimer() {
        if (updateTimer != null) return
        updateTimer = Timer(1000) { tick() }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopSwingTimer() {
        updateTimer?.stop()
        updateTimer = null
    }

    private fun stopSwingTimerIfNoRunning() {
        if (timerStates.values.none { it == TimerState.RUNNING }) {
            stopSwingTimer()
        }
    }

    private fun tick() {
        if (focusedTaskId != null) {
            checkIdleTimeout()
            notifyTimerTick()
        }
    }

    private fun onUserActivity() {
        lastActivityTime = System.currentTimeMillis()

        // Auto-resume if was auto-paused
        if (wasAutoPaused && focusedTaskId != null) {
            wasAutoPaused = false
            resumeFocus()
        }
    }

    private fun checkIdleTimeout() {
        val settings = QuickTodoSettings.getInstance()

        if (!settings.isAutoPauseEnabled()) return
        if (focusedTaskId == null) return
        if (timerStates[focusedTaskId] != TimerState.RUNNING) return

        val idleThresholdMs = settings.getIdleMinutes() * 60 * 1000L
        val idleTime = System.currentTimeMillis() - lastActivityTime

        if (idleTime >= idleThresholdMs && !wasAutoPaused) {
            wasAutoPaused = true
            pauseFocus()
        }
    }

    fun addListener(listener: FocusChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: FocusChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyFocusChanged() {
        listeners.forEach { it.onFocusChanged(focusedTaskId) }
    }

    private fun notifyTimerTick() {
        listeners.forEach { it.onTimerTick() }
    }

    override fun dispose() {
        IdeEventQueue.getInstance().removeDispatcher(activityListener)
        stopSwingTimer()

        val focusedId = focusedTaskId
        if (focusedId != null) {
            stopTimerAndAccumulate(focusedId)
            pauseParentTimers(focusedId)
        }

        focusedTaskId = null
        timerStates.clear()
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): FocusService {
            return project.getService(FocusService::class.java)
        }
    }
}
