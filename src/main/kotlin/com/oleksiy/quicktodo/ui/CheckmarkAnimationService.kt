package com.oleksiy.quicktodo.ui

import java.awt.Rectangle
import javax.swing.Timer

/**
 * Service that manages animated checkmark effects when tasks are completed.
 * Uses a Swing Timer for smooth animation at ~60fps.
 */
class CheckmarkAnimationService {

    companion object {
        private const val ANIMATION_DURATION_MS = 400
        private const val FRAME_INTERVAL_MS = 16 // ~60fps
    }

    data class AnimationState(
        val startTime: Long,
        val bounds: Rectangle
    ) {
        fun getProgress(): Float {
            val elapsed = System.currentTimeMillis() - startTime
            return (elapsed.toFloat() / ANIMATION_DURATION_MS).coerceIn(0f, 1f)
        }

        fun isComplete(): Boolean = getProgress() >= 1f
    }

    private val animations = mutableMapOf<String, AnimationState>()
    private var timer: Timer? = null
    private var repaintCallback: (() -> Unit)? = null

    fun setRepaintCallback(callback: () -> Unit) {
        repaintCallback = callback
    }

    /**
     * Starts a checkmark animation for the given task at the specified bounds.
     */
    fun startAnimation(taskId: String, bounds: Rectangle) {
        animations[taskId] = AnimationState(
            startTime = System.currentTimeMillis(),
            bounds = Rectangle(bounds) // Copy to avoid mutation
        )
        ensureTimerRunning()
    }

    /**
     * Returns the animation state for a task, or null if not animating.
     */
    fun getAnimationState(taskId: String): AnimationState? = animations[taskId]

    /**
     * Returns all currently active animations.
     */
    fun getActiveAnimations(): Map<String, AnimationState> = animations.toMap()

    /**
     * Returns true if any animations are currently active.
     */
    fun hasActiveAnimations(): Boolean = animations.isNotEmpty()

    private fun ensureTimerRunning() {
        if (timer == null) {
            timer = Timer(FRAME_INTERVAL_MS) { onTick() }.apply {
                isRepeats = true
                start()
            }
        }
    }

    private fun onTick() {
        // Remove completed animations
        val completed = animations.filter { it.value.isComplete() }
        completed.keys.forEach { animations.remove(it) }

        // Stop timer if no more animations
        if (animations.isEmpty()) {
            timer?.stop()
            timer = null
        }

        // Request repaint
        repaintCallback?.invoke()
    }

    fun dispose() {
        timer?.stop()
        timer = null
        animations.clear()
        repaintCallback = null
    }
}
