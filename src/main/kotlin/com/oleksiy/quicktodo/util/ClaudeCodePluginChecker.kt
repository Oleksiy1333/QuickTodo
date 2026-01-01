package com.oleksiy.quicktodo.util

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/**
 * Utility to check if the Claude Code plugin is installed and enabled.
 */
object ClaudeCodePluginChecker {

    private const val CLAUDE_CODE_PLUGIN_ID = "com.anthropic.code.plugin"

    /**
     * Checks if the Claude Code plugin is installed and enabled.
     */
    fun isClaudeCodeInstalled(): Boolean {
        val pluginId = PluginId.getId(CLAUDE_CODE_PLUGIN_ID)
        val plugin = PluginManagerCore.getPlugin(pluginId)
        return plugin != null && plugin.isEnabled
    }
}
