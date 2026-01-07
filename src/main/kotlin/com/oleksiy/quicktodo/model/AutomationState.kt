package com.oleksiy.quicktodo.model

/**
 * States for the Claude automation workflow.
 */
enum class AutomationState {
    /** Not running - automation is idle */
    IDLE,

    /** Running claude --permission-mode plan for current task */
    WORKING,

    /** User requested pause - will stop after current operation completes */
    PAUSED
}
