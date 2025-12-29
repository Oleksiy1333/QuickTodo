package com.oleksiy.quicktodo.editor

import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages gutter markers for tasks linked to code locations.
 * Adds markers when editors open, updates when tasks change.
 */
@Service(Service.Level.PROJECT)
class TaskEditorMarkerService(private val project: Project) : Disposable {

    private val taskService = TaskService.getInstance(project)
    private val editorHighlighters = ConcurrentHashMap<Editor, MutableList<RangeHighlighter>>()
    private var taskListener: (() -> Unit)? = null

    init {
        setupTaskListener()
        setupEditorListener()
        // Add markers to any already-open editors
        refreshAllMarkers()
    }

    private fun setupTaskListener() {
        taskListener = { refreshAllMarkers() }
        taskService.addListener(taskListener!!)
    }

    private fun setupEditorListener() {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project == project) {
                    addMarkersToEditor(editor)
                }
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                val editor = event.editor
                removeMarkersFromEditor(editor)
            }
        }, this)
    }

    /**
     * Adds markers to all open editors for the current project.
     */
    fun refreshAllMarkers() {
        EditorFactory.getInstance().allEditors
            .filter { it.project == project }
            .forEach { editor ->
                removeMarkersFromEditor(editor)
                addMarkersToEditor(editor)
            }
    }

    /**
     * Adds task markers to a specific editor.
     */
    fun addMarkersToEditor(editor: Editor) {
        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return

        val basePath = project.basePath ?: return
        val filePath = virtualFile.path

        // Convert to relative path
        val relativePath = if (filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/")
        } else {
            return // File outside project, skip
        }

        val tasksForFile = getTasksForFile(relativePath)
        if (tasksForFile.isEmpty()) return

        val highlighters = mutableListOf<RangeHighlighter>()

        for (task in tasksForFile) {
            val location = task.codeLocation ?: continue
            val line = location.line

            // Validate line number
            if (line < 0 || line >= document.lineCount) continue

            val offset = document.getLineStartOffset(line)
            val highlighter = editor.markupModel.addRangeHighlighter(
                offset,
                offset,
                HighlighterLayer.LAST,
                null, // No text attributes
                HighlighterTargetArea.LINES_IN_RANGE
            )
            highlighter.gutterIconRenderer = TaskGutterIconRenderer(task, project)
            highlighters.add(highlighter)
        }

        if (highlighters.isNotEmpty()) {
            editorHighlighters[editor] = highlighters
        }
    }

    /**
     * Removes all task markers from a specific editor.
     */
    fun removeMarkersFromEditor(editor: Editor) {
        editorHighlighters.remove(editor)?.forEach { highlighter ->
            if (highlighter.isValid) {
                editor.markupModel.removeHighlighter(highlighter)
            }
        }
    }

    /**
     * Finds all tasks that have a code location matching the given file path.
     * Only includes incomplete tasks - completed tasks don't show gutter icons.
     */
    private fun getTasksForFile(relativePath: String): List<Task> {
        val result = mutableListOf<Task>()

        fun collectTasks(tasks: List<Task>) {
            for (task in tasks) {
                if (task.codeLocation?.relativePath == relativePath && !task.isCompleted) {
                    result.add(task)
                }
                collectTasks(task.subtasks)
            }
        }

        collectTasks(taskService.getTasks())
        return result
    }

    override fun dispose() {
        taskListener?.let { taskService.removeListener(it) }
        taskListener = null

        // Clean up all highlighters
        editorHighlighters.keys.toList().forEach { editor ->
            removeMarkersFromEditor(editor)
        }
        editorHighlighters.clear()
    }

    companion object {
        fun getInstance(project: Project): TaskEditorMarkerService {
            return project.getService(TaskEditorMarkerService::class.java)
        }
    }
}
