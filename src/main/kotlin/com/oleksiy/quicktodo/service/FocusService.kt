package com.oleksiy.quicktodo.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.oleksiy.quicktodo.model.TimerState
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

    private val taskService: TaskService
        get() = TaskService.getInstance(project)

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

    fun getElapsedTime(taskId: String): Long {
        val task = taskService.findTask(taskId) ?: return 0
        val baseTime = task.totalTimeSpentMs
        val focusStart = task.lastFocusStartedAt

        return if (focusStart != null && timerStates[taskId] == TimerState.RUNNING) {
            baseTime + (System.currentTimeMillis() - focusStart)
        } else {
            baseTime
        }
    }

    fun getFormattedTime(taskId: String): String {
        val totalMs = getElapsedTime(taskId)
        return formatTime(totalMs)
    }

    fun hasAccumulatedTime(taskId: String): Boolean {
        val task = taskService.findTask(taskId) ?: return false
        return task.totalTimeSpentMs > 0 || timerStates[taskId] == TimerState.RUNNING
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

    private fun stopTimerAndAccumulate(taskId: String) {
        val task = taskService.findTask(taskId) ?: return
        val focusStart = task.lastFocusStartedAt
        if (focusStart != null) {
            task.totalTimeSpentMs += System.currentTimeMillis() - focusStart
            task.lastFocusStartedAt = null
        }
        timerStates[taskId] = TimerState.STOPPED
    }

    private fun pauseTimer(taskId: String) {
        val task = taskService.findTask(taskId) ?: return
        val focusStart = task.lastFocusStartedAt
        if (focusStart != null) {
            task.totalTimeSpentMs += System.currentTimeMillis() - focusStart
            task.lastFocusStartedAt = null
        }
        timerStates[taskId] = TimerState.PAUSED
    }

    private fun startParentTimers(taskId: String) {
        var currentId = taskId
        while (true) {
            val parentId = taskService.findParentId(currentId) ?: break
            startTimer(parentId)
            currentId = parentId
        }
    }

    private fun pauseParentTimers(taskId: String) {
        var currentId = taskId
        while (true) {
            val parentId = taskService.findParentId(currentId) ?: break
            pauseTimer(parentId)
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
            notifyTimerTick()
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
