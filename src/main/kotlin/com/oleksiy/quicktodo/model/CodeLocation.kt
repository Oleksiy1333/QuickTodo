package com.oleksiy.quicktodo.model

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag

/**
 * Represents a location in code (file path + cursor position).
 * Uses relative path for project portability.
 */
@Tag("codeLocation")
data class CodeLocation(
    @Attribute("relativePath")
    var relativePath: String = "",

    @Attribute("line")
    var line: Int = 0,  // 0-based line number

    @Attribute("column")
    var column: Int = 0  // 0-based column number
) {
    constructor() : this("", 0, 0)

    /**
     * Returns display string like "TaskService.kt:42" (1-based line for display)
     */
    fun toDisplayString(): String {
        val fileName = relativePath.substringAfterLast('/')
        return "$fileName:${line + 1}"
    }

    fun isValid(): Boolean = relativePath.isNotBlank()
}
