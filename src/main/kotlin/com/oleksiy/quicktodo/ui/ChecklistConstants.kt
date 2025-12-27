package com.oleksiy.quicktodo.ui

/**
 * Constants used throughout the checklist UI components.
 */
object ChecklistConstants {
    /** Maximum nesting level for subtasks (0-indexed, so 2 means 3 levels total) */
    const val MAX_NESTING_LEVEL = 2

    /** Width of checkbox in tree cells (pixels) */
    const val CHECKBOX_WIDTH = 16

    /** Drop zone threshold - upper quarter of row triggers ABOVE placement */
    const val DROP_ZONE_UPPER_DIVISOR = 4

    /** Drop zone threshold - lower three-quarters of row triggers BELOW placement */
    const val DROP_ZONE_LOWER_FRACTION = 0.75

    /** Corner radius for drop indicator rectangle */
    const val DROP_INDICATOR_CORNER_RADIUS = 6

    /** Stroke width for drop indicator lines */
    const val DROP_INDICATOR_STROKE_WIDTH = 2f

    /** Size of drop indicator circle at line start */
    const val DROP_INDICATOR_CIRCLE_SIZE = 6
}
