package com.oleksiy.quicktodo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "com.oleksiy.quicktodo.settings.QuickTodoSettings",
    storages = [Storage("quicktodo.settings.xml")]
)
@Service(Service.Level.APP)
class QuickTodoSettings : PersistentStateComponent<QuickTodoSettings.State> {

    class State {
        var tooltipBehavior: TooltipBehavior = TooltipBehavior.TRUNCATED
        var autoPauseEnabled: Boolean = true
        var idleMinutes: Int = 5
        var recentTasksCount: Int = 10
        var accumulateHierarchyTime: Boolean = true
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    fun getTooltipBehavior(): TooltipBehavior = myState.tooltipBehavior

    fun setTooltipBehavior(behavior: TooltipBehavior) {
        myState.tooltipBehavior = behavior
    }

    fun isAutoPauseEnabled(): Boolean = myState.autoPauseEnabled

    fun setAutoPauseEnabled(enabled: Boolean) {
        myState.autoPauseEnabled = enabled
    }

    fun getIdleMinutes(): Int = myState.idleMinutes

    fun setIdleMinutes(minutes: Int) {
        myState.idleMinutes = minutes.coerceIn(1, 60)
    }

    fun getRecentTasksCount(): Int = myState.recentTasksCount

    fun setRecentTasksCount(count: Int) {
        myState.recentTasksCount = count.coerceIn(5, 50)
    }

    fun isAccumulateHierarchyTime(): Boolean = myState.accumulateHierarchyTime

    fun setAccumulateHierarchyTime(enabled: Boolean) {
        myState.accumulateHierarchyTime = enabled
    }

    companion object {
        fun getInstance(): QuickTodoSettings {
            return ApplicationManager.getApplication().getService(QuickTodoSettings::class.java)
        }
    }
}
