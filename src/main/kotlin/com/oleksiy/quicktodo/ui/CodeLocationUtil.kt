package com.oleksiy.quicktodo.ui

import com.oleksiy.quicktodo.model.CodeLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Utility for capturing and navigating to code locations.
 */
object CodeLocationUtil {

    /**
     * Captures the current cursor position from the active editor.
     * @return CodeLocation with relative path, or null if no editor is open
     */
    fun captureCurrentLocation(project: Project): CodeLocation? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val virtualFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null

        val basePath = project.basePath ?: return null
        val absolutePath = virtualFile.path

        // Convert to relative path
        val relativePath = if (absolutePath.startsWith(basePath)) {
            absolutePath.removePrefix(basePath).removePrefix("/")
        } else {
            // File is outside project - use absolute path
            absolutePath
        }

        val caretModel = editor.caretModel
        val logicalPosition = caretModel.logicalPosition

        return CodeLocation(
            relativePath = relativePath,
            line = logicalPosition.line,
            column = logicalPosition.column
        )
    }

    /**
     * Navigates to the specified code location.
     * @return true if navigation succeeded
     */
    fun navigateToLocation(project: Project, location: CodeLocation): Boolean {
        val basePath = project.basePath ?: return false

        // Resolve path (relative or absolute)
        val absolutePath = if (location.relativePath.startsWith("/")) {
            location.relativePath
        } else {
            File(basePath, location.relativePath).absolutePath
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return false

        val descriptor = OpenFileDescriptor(
            project,
            virtualFile,
            location.line,
            location.column
        )

        return descriptor.navigateInEditor(project, true)
    }
}
