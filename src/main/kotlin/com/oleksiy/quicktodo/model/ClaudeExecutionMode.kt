package com.oleksiy.quicktodo.model

/**
 * Execution modes for Claude Code commands.
 * Each mode represents different permission levels when running tasks.
 */
enum class ClaudeExecutionMode(
    val displayName: String,
    val description: String,
    val commandArgs: String
) {
    DEFAULT(
        displayName = "Default",
        description = "Uses the default mode selected above",
        commandArgs = "" // Resolved at runtime to the actual default mode
    ),
    PLAN(
        displayName = "Plan Mode",
        description = "Interactive review - you approve each change",
        commandArgs = "--permission-mode plan"
    ),
    ACCEPT_EDITS(
        displayName = "Accept Edits",
        description = "Auto-accepts file operations (Edit, Write, Read, Glob, Grep)",
        commandArgs = "--allowedTools \"Edit,Write,Read,Glob,Grep\""
    ),
    SKIP_PERMISSIONS(
        displayName = "Skip Permissions",
        description = "Runs without any permission checks (use with caution)",
        commandArgs = "--dangerously-skip-permissions"
    );

    companion object {
        fun fromString(value: String?): ClaudeExecutionMode {
            return entries.find { it.name == value } ?: DEFAULT
        }

        /**
         * Returns modes available for the "Default mode:" bulk selector (excludes DEFAULT).
         */
        fun bulkModes(): Array<ClaudeExecutionMode> {
            return entries.filter { it != DEFAULT }.toTypedArray()
        }
    }
}
