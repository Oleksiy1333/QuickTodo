package com.oleksiy.quicktodo.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JTree

/**
 * Paints drop indicators for drag and drop operations in the task tree.
 */
object DropIndicatorPainter {

    fun paint(g: Graphics, tree: JTree, targetRow: Int, position: DropPosition) {
        val rowBounds = tree.getRowBounds(targetRow) ?: return
        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val accentColor = JBUI.CurrentTheme.Focus.focusColor()
        g2d.color = accentColor
        g2d.stroke = BasicStroke(ChecklistConstants.DROP_INDICATOR_STROKE_WIDTH)

        val x = rowBounds.x + ChecklistConstants.CHECKBOX_WIDTH
        val width = tree.width - x - 4
        val circleRadius = ChecklistConstants.DROP_INDICATOR_CIRCLE_SIZE / 2

        when (position) {
            DropPosition.ABOVE -> {
                val y = rowBounds.y
                g2d.drawLine(x, y, x + width, y)
                g2d.fillOval(
                    x - circleRadius,
                    y - circleRadius,
                    ChecklistConstants.DROP_INDICATOR_CIRCLE_SIZE,
                    ChecklistConstants.DROP_INDICATOR_CIRCLE_SIZE
                )
            }
            DropPosition.BELOW -> {
                val y = rowBounds.y + rowBounds.height
                g2d.drawLine(x, y, x + width, y)
                g2d.fillOval(
                    x - circleRadius,
                    y - circleRadius,
                    ChecklistConstants.DROP_INDICATOR_CIRCLE_SIZE,
                    ChecklistConstants.DROP_INDICATOR_CIRCLE_SIZE
                )
            }
            DropPosition.AS_CHILD -> {
                val cornerRadius = ChecklistConstants.DROP_INDICATOR_CORNER_RADIUS
                g2d.stroke = BasicStroke(
                    ChecklistConstants.DROP_INDICATOR_STROKE_WIDTH,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    0f,
                    floatArrayOf(4f, 4f),
                    0f
                )
                g2d.drawRoundRect(
                    rowBounds.x + 2,
                    rowBounds.y + 1,
                    rowBounds.width - 4,
                    rowBounds.height - 2,
                    cornerRadius,
                    cornerRadius
                )
            }
            DropPosition.NONE -> Unit
        }
    }
}
