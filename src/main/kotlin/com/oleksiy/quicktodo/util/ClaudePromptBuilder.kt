package com.oleksiy.quicktodo.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.oleksiy.quicktodo.model.CodeLocation
import com.oleksiy.quicktodo.model.Priority
import com.oleksiy.quicktodo.model.Task
import com.oleksiy.quicktodo.service.TaskService
import java.io.File

/**
 * Builds enhanced prompts for Claude Code with additional context.
 */
object ClaudePromptBuilder {

    private const val MAX_SNIPPET_LINES = 30
    private const val DEFAULT_CONTEXT_LINES = 15
    private const val MAX_HISTORY_TASKS = 10

    /**
     * Builds a complete prompt with project context, task details, code snippets, and session history.
     */
    fun buildPrompt(
        project: Project,
        task: Task,
        completedTaskIds: List<String>,
        taskService: TaskService,
        askMoreQuestions: Boolean = false
    ): String {
        val sb = StringBuilder()

        // Project context
        sb.append(buildProjectContext(project))
        sb.append("\n")

        // Instructions (if ask more questions is enabled)
        if (askMoreQuestions) {
            sb.append(buildInstructions())
            sb.append("\n")
        }

        // Task section
        sb.append(buildTaskSection(project, task))

        // Session history
        val history = buildSessionHistory(completedTaskIds, taskService)
        if (history.isNotBlank()) {
            sb.append("\n")
            sb.append(history)
        }

        return sb.toString()
    }

    private fun buildProjectContext(project: Project): String {
        val sb = StringBuilder()
        sb.appendLine("=== PROJECT CONTEXT ===")
        sb.appendLine("Project: ${project.name}")
        project.basePath?.let { sb.appendLine("Working Directory: $it") }
        return sb.toString()
    }

    private fun buildInstructions(): String {
        val sb = StringBuilder()
        sb.appendLine("=== INSTRUCTIONS ===")
        sb.appendLine("Before implementing, ask the user clarifying questions to ensure you understand")
        sb.appendLine("the requirements correctly. Brainstorm different approaches and discuss trade-offs")
        sb.appendLine("with the user. Take time to understand the full scope before writing code.")
        return sb.toString()
    }

    private fun buildTaskSection(project: Project, task: Task): String {
        val sb = StringBuilder()
        sb.appendLine("=== TASK ===")

        // Priority (if not NONE)
        val priority = task.getPriorityEnum()
        if (priority != Priority.NONE) {
            sb.appendLine("[Priority: ${priority.displayName.uppercase()}]")
            sb.appendLine()
        }

        // Task name
        sb.appendLine(task.text)

        // Description (if present)
        if (task.hasDescription()) {
            sb.appendLine()
            sb.appendLine("Description:")
            task.description.lines().forEach { line ->
                sb.appendLine("  $line")
            }
        }

        // Code location and snippet
        if (task.hasCodeLocation()) {
            val codeLocation = task.codeLocation!!
            sb.appendLine()
            sb.appendLine("Code Location: ${codeLocation.relativePath}:${codeLocation.line + 1}")

            val snippet = extractCodeSnippet(project, codeLocation)
            if (snippet != null) {
                sb.appendLine("--- Code Context ---")
                sb.appendLine(snippet)
                sb.appendLine("---")
            }
        }

        // Subtasks
        if (task.subtasks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Subtasks:")
            appendSubtasks(sb, task.subtasks, 1)
        }

        return sb.toString()
    }

    private fun appendSubtasks(sb: StringBuilder, subtasks: List<Task>, depth: Int) {
        val indent = "  ".repeat(depth)
        for (subtask in subtasks) {
            val status = if (subtask.isCompleted) "[x]" else "[ ]"
            sb.appendLine("$indent- $status ${subtask.text}")

            // Include subtask description if present
            if (subtask.hasDescription()) {
                subtask.description.lines().forEach { line ->
                    sb.appendLine("$indent    $line")
                }
            }

            if (subtask.subtasks.isNotEmpty()) {
                appendSubtasks(sb, subtask.subtasks, depth + 1)
            }
        }
    }

    private fun extractCodeSnippet(
        project: Project,
        codeLocation: CodeLocation,
        contextLines: Int = DEFAULT_CONTEXT_LINES
    ): String? {
        val basePath = project.basePath ?: return null
        val absolutePath = File(basePath, codeLocation.relativePath).absolutePath
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null

        // Check if file is text-based
        if (virtualFile.fileType.isBinary) return null

        val document: Document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        val totalLines = document.lineCount
        if (totalLines == 0) return null

        // Calculate line range with context
        val targetLine = codeLocation.line.coerceIn(0, totalLines - 1)
        val startLine: Int
        val endLine: Int

        if (codeLocation.hasSelection()) {
            // Use selection range with some context
            startLine = maxOf(0, codeLocation.line - 5)
            endLine = minOf(totalLines - 1, codeLocation.endLine + 5)
        } else {
            // Center around target line
            startLine = maxOf(0, targetLine - contextLines)
            endLine = minOf(totalLines - 1, targetLine + contextLines)
        }

        // Limit total lines
        val adjustedEndLine = minOf(endLine, startLine + MAX_SNIPPET_LINES - 1)

        // Extract text
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(adjustedEndLine)
        val snippet = document.getText(TextRange(startOffset, endOffset))

        // Add line numbers
        val lines = snippet.lines()
        val numbered = lines.mapIndexed { index, line ->
            val lineNum = startLine + index + 1
            "${lineNum.toString().padStart(4)}: $line"
        }.joinToString("\n")

        // Determine language for syntax highlighting
        val extension = codeLocation.relativePath.substringAfterLast('.', "")
        val language = extensionToLanguage(extension)

        val sb = StringBuilder()
        sb.appendLine("```$language")
        sb.appendLine("// Lines ${startLine + 1}-${adjustedEndLine + 1} from ${codeLocation.relativePath}")
        sb.append(numbered)
        sb.appendLine()
        sb.append("```")

        return sb.toString()
    }

    private fun extensionToLanguage(ext: String): String = when (ext.lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py" -> "python"
        "js" -> "javascript"
        "ts" -> "typescript"
        "tsx" -> "typescript"
        "jsx" -> "javascript"
        "go" -> "go"
        "rs" -> "rust"
        "rb" -> "ruby"
        "php" -> "php"
        "swift" -> "swift"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp" -> "cpp"
        "cs" -> "csharp"
        "xml" -> "xml"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "md" -> "markdown"
        "sql" -> "sql"
        "sh", "bash" -> "bash"
        else -> ext
    }

    private fun buildSessionHistory(
        completedTaskIds: List<String>,
        taskService: TaskService
    ): String {
        if (completedTaskIds.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        sb.appendLine("=== SESSION HISTORY ===")
        sb.appendLine("Previously completed in this session:")

        // Limit to recent tasks
        val recentIds = completedTaskIds.takeLast(MAX_HISTORY_TASKS)
        recentIds.forEachIndexed { index, taskId ->
            val task = taskService.findTask(taskId)
            if (task != null) {
                val priorityMarker = if (task.getPriorityEnum() != Priority.NONE) {
                    " [${task.getPriorityEnum().displayName}]"
                } else ""
                sb.appendLine("${index + 1}. ${task.text}$priorityMarker")
            }
        }

        return sb.toString()
    }
}
