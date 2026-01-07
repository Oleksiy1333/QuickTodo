package com.oleksiy.quicktodo.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import com.oleksiy.quicktodo.model.ClaudeExecutionMode
import com.oleksiy.quicktodo.model.ClaudeModel
import com.oleksiy.quicktodo.model.TaskExecutionConfig

/**
 * Service for managing AI workflow configuration.
 * Persists to .idea/quicktodo.ai.xml
 */
@State(
    name = "com.oleksiy.quicktodo.AiConfigService",
    storages = [Storage("quicktodo.ai.xml")]
)
@Service(Service.Level.PROJECT)
class AiConfigService : PersistentStateComponent<AiConfigService.State> {

    class State {
        @XCollection(propertyElementName = "selectedTaskIds", elementName = "id")
        var selectedTaskIds: MutableList<String> = mutableListOf()

        @XCollection(propertyElementName = "taskConfigs", elementName = "taskConfig")
        var taskConfigs: MutableList<TaskExecutionConfig> = mutableListOf()

        @XCollection(propertyElementName = "completedTaskIds", elementName = "id")
        var completedTaskIds: MutableList<String> = mutableListOf()

        var autoContinue: Boolean = true
        var askMoreQuestions: Boolean = false
        var commitAfterTask: Boolean = false
        var currentIndex: Int = 0
        var status: String = STATUS_IDLE
        var defaultExecutionMode: String = ClaudeExecutionMode.PLAN.name
        var selectedModel: String = ClaudeModel.default().modelId
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    // ============ Configuration Accessors ============

    fun getSelectedTaskIds(): List<String> = myState.selectedTaskIds.toList()

    fun setSelectedTaskIds(taskIds: List<String>) {
        myState.selectedTaskIds.clear()
        myState.selectedTaskIds.addAll(taskIds)
    }

    fun isAutoContinue(): Boolean = myState.autoContinue

    fun setAutoContinue(value: Boolean) {
        myState.autoContinue = value
    }

    fun isAskMoreQuestions(): Boolean = myState.askMoreQuestions

    fun setAskMoreQuestions(value: Boolean) {
        myState.askMoreQuestions = value
    }

    fun isCommitAfterTask(): Boolean = myState.commitAfterTask

    fun setCommitAfterTask(value: Boolean) {
        myState.commitAfterTask = value
    }

    fun getSelectedModel(): ClaudeModel = ClaudeModel.fromModelId(myState.selectedModel)

    fun setSelectedModel(model: ClaudeModel) {
        myState.selectedModel = model.modelId
    }

    // ============ Execution Mode Management ============

    fun getExecutionMode(taskId: String): ClaudeExecutionMode {
        val config = myState.taskConfigs.find { it.taskId == taskId }
        return config?.getExecutionModeEnum() ?: getDefaultExecutionMode()
    }

    fun setExecutionMode(taskId: String, mode: ClaudeExecutionMode) {
        val existing = myState.taskConfigs.find { it.taskId == taskId }
        if (existing != null) {
            existing.setExecutionModeEnum(mode)
        } else {
            myState.taskConfigs.add(TaskExecutionConfig(taskId, mode.name))
        }
    }

    fun setAllExecutionModes(taskIds: List<String>, mode: ClaudeExecutionMode) {
        taskIds.forEach { taskId ->
            setExecutionMode(taskId, mode)
        }
    }

    fun getDefaultExecutionMode(): ClaudeExecutionMode {
        return ClaudeExecutionMode.fromString(myState.defaultExecutionMode)
    }

    fun setDefaultExecutionMode(mode: ClaudeExecutionMode) {
        myState.defaultExecutionMode = mode.name
    }

    fun clearTaskConfigs() {
        myState.taskConfigs.clear()
    }

    fun getTaskConfigs(): List<TaskExecutionConfig> = myState.taskConfigs.toList()

    fun setTaskConfigs(configs: List<TaskExecutionConfig>) {
        myState.taskConfigs.clear()
        myState.taskConfigs.addAll(configs)
    }

    // ============ Progress Tracking ============

    fun getCurrentIndex(): Int = myState.currentIndex

    fun setCurrentIndex(index: Int) {
        myState.currentIndex = index
    }

    fun getStatus(): String = myState.status

    fun setStatus(status: String) {
        myState.status = status
    }

    fun getCurrentTaskId(): String? {
        val ids = myState.selectedTaskIds
        val index = myState.currentIndex
        return if (index in ids.indices) ids[index] else null
    }

    fun hasNextTask(): Boolean {
        return myState.currentIndex + 1 < myState.selectedTaskIds.size
    }

    fun moveToNextTask(): Boolean {
        if (hasNextTask()) {
            myState.currentIndex++
            return true
        }
        return false
    }

    // ============ Completed Task Tracking ============

    fun getCompletedTaskIds(): List<String> = myState.completedTaskIds.toList()

    fun addCompletedTaskId(taskId: String) {
        if (taskId !in myState.completedTaskIds) {
            myState.completedTaskIds.add(taskId)
        }
    }

    fun clearCompletedTaskIds() {
        myState.completedTaskIds.clear()
    }

    // ============ Session Management ============

    fun startSession(taskIds: List<String>, autoContinue: Boolean) {
        myState.selectedTaskIds.clear()
        myState.selectedTaskIds.addAll(taskIds)
        myState.completedTaskIds.clear()  // Clear history for new session
        myState.autoContinue = autoContinue
        myState.currentIndex = 0
        myState.status = STATUS_IN_PROGRESS
    }

    fun completeSession() {
        myState.status = STATUS_COMPLETED
    }

    fun pauseSession() {
        myState.status = STATUS_PAUSED
    }

    fun resumeSession() {
        myState.status = STATUS_IN_PROGRESS
    }

    fun resetSession() {
        myState.selectedTaskIds.clear()
        myState.taskConfigs.clear()
        myState.completedTaskIds.clear()
        myState.currentIndex = 0
        myState.status = STATUS_IDLE
    }

    fun isSessionActive(): Boolean {
        return myState.status == STATUS_IN_PROGRESS || myState.status == STATUS_PAUSED
    }

    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"

        fun getInstance(project: Project): AiConfigService {
            return project.getService(AiConfigService::class.java)
        }
    }
}
