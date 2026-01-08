package com.oleksiy.quicktodo.settings

/**
 * Defines where new root-level tasks are inserted in the task list.
 */
enum class TaskInsertionPosition(val displayName: String) {
    TOP("Top of list"),
    BOTTOM("Bottom of list")
}
