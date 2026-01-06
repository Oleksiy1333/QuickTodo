package com.oleksiy.quicktodo.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.io.File
import javax.swing.Timer

/**
 * Monitors Claude Code completion via Stop hook signal files.
 *
 * This class manages the lifecycle of signal files used to detect when Claude Code
 * finishes execution. It configures a Stop hook in the project's .claude/settings.json
 * that writes to a signal file when Claude stops.
 */
class ClaudeSignalMonitor(private val project: Project) {

    companion object {
        private const val SIGNAL_DIR = "/tmp"
        private const val SIGNAL_FILE = "quicktodo-signal.txt"
        private const val COMPLETION_MARKER = "QUICKTODO_COMPLETE"
        private const val POLL_INTERVAL_MS = 1000 // 1 second
        private const val TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    private var pollTimer: Timer? = null
    private var startTime: Long = 0
    private var onComplete: (() -> Unit)? = null
    private var onTimeout: (() -> Unit)? = null

    /**
     * Gets the path to the signal file.
     */
    val signalFilePath: String
        get() = "$SIGNAL_DIR/$SIGNAL_FILE"

    /**
     * Starts monitoring for completion.
     * Configures the Stop hook and begins polling for Claude's completion signal.
     *
     * @param onComplete Callback when Claude completes successfully
     * @param onTimeout Callback when timeout is reached without completion
     */
    fun startMonitoring(onComplete: () -> Unit, onTimeout: () -> Unit) {
        this.onComplete = onComplete
        this.onTimeout = onTimeout
        startTime = System.currentTimeMillis()

        // Clean up any existing signal file
        File(signalFilePath).delete()

        // Configure the Stop hook
        configureStopHook()

        // Start polling
        pollTimer = Timer(POLL_INTERVAL_MS) { checkForCompletion() }.apply {
            isRepeats = true
            start()
        }
    }

    /**
     * Stops monitoring and cleans up resources.
     */
    fun stopMonitoring() {
        pollTimer?.stop()
        pollTimer = null

        // Clean up signal file
        File(signalFilePath).delete()

        onComplete = null
        onTimeout = null
    }

    /**
     * Checks if monitoring is currently active.
     */
    fun isMonitoring(): Boolean = pollTimer != null

    private fun checkForCompletion() {
        val signalFile = File(signalFilePath)

        // Check for completion marker in signal file
        if (signalFile.exists()) {
            val content = signalFile.readText()
            if (content.contains(COMPLETION_MARKER)) {
                pollTimer?.stop()
                pollTimer = null
                signalFile.delete()
                onComplete?.invoke()
                return
            }
        }

        // Check for timeout
        if (System.currentTimeMillis() - startTime > TIMEOUT_MS) {
            pollTimer?.stop()
            pollTimer = null
            signalFile.delete()
            onTimeout?.invoke()
        }
    }

    /**
     * Configures the Stop hook in the project's .claude/settings.json file.
     * Creates or updates the settings file to include a hook that signals completion.
     */
    private fun configureStopHook() {
        val projectPath = project.basePath ?: return
        val claudeDir = File(projectPath, ".claude")
        val settingsFile = File(claudeDir, "settings.json")

        // Ensure .claude directory exists
        if (!claudeDir.exists()) {
            claudeDir.mkdirs()
        }

        // Read existing settings or create new
        val settings: JsonObject = if (settingsFile.exists()) {
            try {
                JsonParser.parseString(settingsFile.readText()).asJsonObject
            } catch (e: Exception) {
                JsonObject()
            }
        } else {
            JsonObject()
        }

        // Simple hook that writes completion marker to signal file
        val hookCommand = "echo '$COMPLETION_MARKER' >> $signalFilePath"

        val hookConfig = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", hookCommand)
        }

        val hookMatcher = JsonObject().apply {
            addProperty("matcher", "*")
            add("hooks", com.google.gson.JsonArray().apply { add(hookConfig) })
        }

        val stopHooks = com.google.gson.JsonArray().apply { add(hookMatcher) }

        // Get or create hooks object
        val hooks = if (settings.has("hooks")) {
            settings.getAsJsonObject("hooks")
        } else {
            JsonObject().also { settings.add("hooks", it) }
        }

        // Add/update Stop hook
        hooks.add("Stop", stopHooks)

        // Write settings back
        val gson = GsonBuilder().setPrettyPrinting().create()
        settingsFile.writeText(gson.toJson(settings))
    }

    /**
     * Removes the Stop hook configuration from the project's settings.
     * Call this when automation is completely stopped.
     */
    fun removeStopHook() {
        val projectPath = project.basePath ?: return
        val settingsFile = File(projectPath, ".claude/settings.json")

        if (!settingsFile.exists()) return

        try {
            val settings = JsonParser.parseString(settingsFile.readText()).asJsonObject
            val hooks = settings.getAsJsonObject("hooks")
            hooks?.remove("Stop")

            // If hooks object is empty, remove it
            if (hooks != null && hooks.size() == 0) {
                settings.remove("hooks")
            }

            val gson = GsonBuilder().setPrettyPrinting().create()
            settingsFile.writeText(gson.toJson(settings))
        } catch (e: Exception) {
            // Ignore errors when cleaning up
        }
    }
}
