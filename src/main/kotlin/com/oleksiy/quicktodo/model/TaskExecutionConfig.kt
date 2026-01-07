package com.oleksiy.quicktodo.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag

/**
 * Configuration for a single task's Claude Code execution.
 * Used for persisting per-task execution mode preferences.
 */
@Tag("taskConfig")
data class TaskExecutionConfig(
    @Attribute("taskId")
    var taskId: String = "",

    @Attribute("executionMode")
    var executionMode: String = ClaudeExecutionMode.PLAN.name
) {
    constructor() : this("", ClaudeExecutionMode.PLAN.name)

    fun getExecutionModeEnum(): ClaudeExecutionMode =
        ClaudeExecutionMode.fromString(executionMode)

    fun setExecutionModeEnum(mode: ClaudeExecutionMode) {
        executionMode = mode.name
    }
}
