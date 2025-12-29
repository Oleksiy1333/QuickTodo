package com.oleksiy.quicktodo.ui

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import kotlin.math.min

/**
 * Paints an animated checkmark with scale and fade effects.
 */
object CheckmarkPainter {

    private val CHECKMARK_COLOR = JBColor(
        Color(76, 175, 80),  // Green for light theme
        Color(129, 199, 132) // Lighter green for dark theme
    )

    private val CIRCLE_COLOR = JBColor(
        Color(76, 175, 80, 40),  // Semi-transparent green
        Color(129, 199, 132, 40)
    )

    /**
     * Paints an animated checkmark at the given bounds with the specified progress (0.0 to 1.0).
     *
     * Animation phases:
     * - 0.0-0.3: Circle expands, checkmark draws stroke-by-stroke
     * - 0.3-0.7: Full checkmark visible
     * - 0.7-1.0: Fade out
     */
    fun paint(g: Graphics2D, bounds: Rectangle, progress: Float) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            // Calculate center and size - position at the checkbox location
            val size = min(bounds.height - 4, 20)
            val centerX = bounds.x + ChecklistConstants.CHECKBOX_WIDTH / 2
            val centerY = bounds.y + bounds.height / 2

            // Calculate animation phases
            val drawProgress = when {
                progress < 0.3f -> progress / 0.3f
                else -> 1f
            }

            val alpha = when {
                progress < 0.7f -> 1f
                else -> 1f - ((progress - 0.7f) / 0.3f)
            }

            // Scale effect: start at 0.5, overshoot to 1.2, settle at 1.0
            val scale = when {
                progress < 0.2f -> 0.5f + (progress / 0.2f) * 0.7f  // 0.5 -> 1.2
                progress < 0.35f -> 1.2f - ((progress - 0.2f) / 0.15f) * 0.2f  // 1.2 -> 1.0
                else -> 1f
            }

            // Draw expanding circle background
            if (alpha > 0) {
                val circleAlpha = (alpha * 0.4f * 255).toInt().coerceIn(0, 255)
                val circleSize = (size * scale * drawProgress).toInt()
                g2.color = Color(
                    CIRCLE_COLOR.red,
                    CIRCLE_COLOR.green,
                    CIRCLE_COLOR.blue,
                    circleAlpha
                )
                g2.fillOval(
                    centerX - circleSize / 2,
                    centerY - circleSize / 2,
                    circleSize,
                    circleSize
                )
            }

            // Draw checkmark
            if (drawProgress > 0 && alpha > 0) {
                val checkAlpha = (alpha * 255).toInt().coerceIn(0, 255)
                g2.color = Color(
                    CHECKMARK_COLOR.red,
                    CHECKMARK_COLOR.green,
                    CHECKMARK_COLOR.blue,
                    checkAlpha
                )
                g2.stroke = BasicStroke(2.5f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                // Checkmark points (relative to center, scaled)
                val checkSize = size * 0.35f * scale

                // Short leg of checkmark (down-left to bottom)
                val x1 = centerX - checkSize * 0.5f
                val y1 = centerY
                val x2 = centerX - checkSize * 0.1f
                val y2 = centerY + checkSize * 0.5f

                // Long leg of checkmark (bottom to up-right)
                val x3 = centerX + checkSize * 0.6f
                val y3 = centerY - checkSize * 0.4f

                // Draw stroke-by-stroke based on progress
                if (drawProgress > 0) {
                    val strokeProgress = drawProgress.coerceIn(0f, 1f)

                    if (strokeProgress <= 0.4f) {
                        // Draw first part of short leg
                        val legProgress = strokeProgress / 0.4f
                        val endX = x1 + (x2 - x1) * legProgress
                        val endY = y1 + (y2 - y1) * legProgress
                        g2.drawLine(x1.toInt(), y1.toInt(), endX.toInt(), endY.toInt())
                    } else {
                        // Draw complete short leg
                        g2.drawLine(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())

                        // Draw long leg based on remaining progress
                        val longLegProgress = (strokeProgress - 0.4f) / 0.6f
                        val endX = x2 + (x3 - x2) * longLegProgress
                        val endY = y2 + (y3 - y2) * longLegProgress
                        g2.drawLine(x2.toInt(), y2.toInt(), endX.toInt(), endY.toInt())
                    }
                }
            }
        } finally {
            g2.dispose()
        }
    }
}
