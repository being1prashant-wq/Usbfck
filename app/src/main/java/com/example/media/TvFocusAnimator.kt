package com.example.media

import android.view.View
import android.view.animation.DecelerateInterpolator

object TvFocusAnimator {
    private val interpolator = DecelerateInterpolator(1.5f)

    fun attach(view: View, scaleFactor: Float = 1.05f) {
        val existingListener = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            existingListener?.onFocusChange(v, hasFocus)
            animateScale(v, hasFocus, scaleFactor)
        }
    }

    private fun animateScale(view: View, hasFocus: Boolean, scaleFactor: Float) {
        val targetScale = if (hasFocus) scaleFactor else 1.0f
        val targetElevation = if (hasFocus) 12f else 0f

        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationZ(targetElevation)
            .setDuration(160)
            .setInterpolator(interpolator)
            .start()
    }
}
