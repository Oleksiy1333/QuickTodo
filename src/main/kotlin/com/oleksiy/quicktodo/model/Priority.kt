package com.oleksiy.quicktodo.model

enum class Priority(val displayName: String) {
    NONE("None"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High");

    companion object {
        fun fromString(value: String?): Priority {
            return entries.find { it.name == value } ?: NONE
        }
    }
}
