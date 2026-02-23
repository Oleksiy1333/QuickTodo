package com.oleksiy.quicktodo.model

import java.time.LocalDate
import java.time.LocalDateTime

enum class TaskDateGroup {
    OVERDUE,
    TODAY,
    NONE
}

object TaskDateHelper {

    fun classifyTask(task: Task, rolloverHour: Int): TaskDateGroup {
        val plannedDate = task.plannedDate ?: return TaskDateGroup.NONE
        val today = resolveToday(rolloverHour)
        return when {
            plannedDate < today -> TaskDateGroup.OVERDUE
            plannedDate == today -> TaskDateGroup.TODAY
            else -> TaskDateGroup.NONE
        }
    }

    fun resolveToday(rolloverHour: Int): String {
        val now = LocalDateTime.now()
        val effectiveDate = if (now.hour < rolloverHour) now.minusDays(1).toLocalDate() else now.toLocalDate()
        return effectiveDate.toString()
    }
}
