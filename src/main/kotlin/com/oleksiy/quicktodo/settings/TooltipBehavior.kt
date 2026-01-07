package com.oleksiy.quicktodo.settings

/**
 * Defines when to show tooltips for tasks.
 */
enum class TooltipBehavior(val displayName: String) {
    ALWAYS("Always show"),
    TRUNCATED("Show if truncated"),
    NEVER("Never show")
}
