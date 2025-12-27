package com.oleksiy.quicktodo.model

enum class TimerState {
    STOPPED,   // Timer not running, no accumulated time from current session
    RUNNING,   // Timer actively counting
    PAUSED     // Timer paused, preserving current session time
}
