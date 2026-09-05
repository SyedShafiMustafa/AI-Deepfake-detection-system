// CircularGaugeView.kt
package com.shafi.deepfakedetector

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * A circular arc gauge used to display the confidence score.
 * The arc sweeps from 135° (bottom-left) clockwise through the top
 * to 45° (bottom-right), and animates toward its target value.
 */
class CircularGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF242C27.toInt()
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcRect = RectF()
    private var currentFraction = 0f
    private var targetFraction = 0f
    private var animator: ValueAnimator? = null
    private var startColor = 0xFF34D399.toInt()
    private var endColor = 0xFF059669.toInt()

    fun setColors(start: Int, end: Int) {
        startColor = start
        endColor = end
        invalidate()
    }

    /** Animate the gauge to [percent] (0..100). */
    fun setProgress(percent: Float, animate: Boolean = true) {
        targetFraction = (percent.coerceIn(0f, 100f)) / 100f
        if (!animate) {
            currentFraction = targetFraction
            invalidate()
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(currentFraction, targetFraction).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                currentFraction = valueAnimator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = min(width, height) * 0.085f
        val margin = stroke / 2 + 8f * density
        arcRect.set(margin, margin, width - margin, height - margin)

        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke

        // Background track (270° arc from 135° to 45°)
        canvas.drawArc(arcRect, 135f, 270f, false, trackPaint)

        if (currentFraction > 0f) {
            progressPaint.shader = SweepGradient(
                width / 2f,
                height / 2f,
                intArrayOf(startColor, endColor),
                floatArrayOf(0f, 1f)
            )
            // Rotate so the gradient (and arc) start at 135° instead of 0°
            canvas.save()
            canvas.rotate(135f, width / 2f, height / 2f)
            canvas.drawArc(arcRect, 0f, 270f * currentFraction, false, progressPaint)
            canvas.restore()
        }
    }
}