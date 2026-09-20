package com.example.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.theme.AppTheme
import com.example.theme.ThemeManager
import kotlin.random.Random

class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 4
    private val barHeights = FloatArray(barCount) { 0.2f }
    private val targetHeights = FloatArray(barCount) { 0.2f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeManager.getTheme().accentColor
        style = Paint.Style.FILL
    }
    private val barRect = RectF()

    private var isPlaying = false
    private var animator: ValueAnimator? = null

    init {
        ThemeManager.addListener { theme ->
            paint.color = theme.accentColor
            invalidate()
        }
    }

    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        if (playing) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    fun setThemeColor(color: Int) {
        paint.color = color
        invalidate()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                for (i in 0 until barCount) {
                    if (isPlaying) {
                        // Smoothly transition heights
                        barHeights[i] = barHeights[i] + (targetHeights[i] - barHeights[i]) * 0.4f
                        if (Math.abs(targetHeights[i] - barHeights[i]) < 0.08f) {
                            targetHeights[i] = 0.2f + Random.nextFloat() * 0.8f
                        }
                    } else {
                        barHeights[i] = barHeights[i] + (0.15f - barHeights[i]) * 0.2f
                    }
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        for (i in 0 until barCount) {
            targetHeights[i] = 0.15f
            barHeights[i] = 0.15f
        }
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val spacing = 6f
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = (w - totalSpacing) / barCount
        val radius = barWidth / 2f

        for (i in 0 until barCount) {
            val left = i * (barWidth + spacing)
            val right = left + barWidth
            val currentH = (h * barHeights[i]).coerceIn(radius * 2f, h)
            val top = h - currentH
            val bottom = h

            barRect.set(left, top, right, bottom)
            canvas.drawRoundRect(barRect, radius, radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
