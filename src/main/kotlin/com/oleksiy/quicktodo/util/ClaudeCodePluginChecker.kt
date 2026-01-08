package com.oleksiy.quicktodo.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Utility to check if the Claude Code plugin is installed and enabled.
 */
object ClaudeCodePluginChecker {

    private const val CLAUDE_CODE_PLUGIN_ID = "com.anthropic.code.plugin"

    /**
     * Set to true during development to bypass Claude Code plugin check.
     * TODO: Set to false before release.
     */
    private const val DEV_MODE_SKIP_CHECK = false

    /**
     * Checks if the Claude Code plugin is installed and enabled.
     */
    fun isClaudeCodeInstalled(): Boolean {
        if (DEV_MODE_SKIP_CHECK) return true
        val pluginId = PluginId.getId(CLAUDE_CODE_PLUGIN_ID)
        return PluginManagerCore.getPlugin(pluginId) != null && !PluginManagerCore.isDisabled(pluginId)
    }
}
