package com.oleksiy.quicktodo.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Initializes the TaskEditorMarkerService when a project opens.
 * This ensures gutter markers appear even before the QuickTodo tool window is opened.
 */
class TaskEditorMarkerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Access the service to trigger initialization
        TaskEditorMarkerService.getInstance(project)
    }
}
