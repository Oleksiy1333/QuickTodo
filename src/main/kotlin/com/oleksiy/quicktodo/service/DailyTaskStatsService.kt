package com.oleksiy.quicktodo.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.oleksiy.quicktodo.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service(Service.Level.PROJECT)
class DailyTaskStatsService(private val project: Project) {

    data class DailyStats(
        val createdToday: Int,
        val completedToday: Int
    )

    fun calculateDailyStats(): DailyStats {
        val taskService = TaskService.getInstance(project)
        val tasks = taskService.getTasks()
        val today = LocalDate.now()

        var createdToday = 0
        var completedToday = 0

        fun traverse(task: Task) {
            if (isToday(task.createdAt, today)) {
                createdToday++
            }
            if (isToday(task.completedAt, today)) {
                completedToday++
            }
            task.subtasks.forEach { traverse(it) }
        }

        tasks.forEach { traverse(it) }

        return DailyStats(createdToday, completedToday)
    }

    private fun isToday(timestamp: Long?, today: LocalDate): Boolean {
        if (timestamp == null) return false
        val date = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return date.isEqual(today)
    }

    companion object {
        fun getInstance(project: Project): DailyTaskStatsService {
            return project.getService(DailyTaskStatsService::class.java)
        }
    }
}
