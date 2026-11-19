package com.authnotes.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.MotionEvent
import com.authnotes.R
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator

/** Uses the source card's bounds for both directions; outside taps use the same dismissal. */
class FloatingEditorDialog(context: Context, source: View?) : Dialog(context, R.style.Theme_AuthNotes_FloatingEditor) {
    private val sourceBounds = source?.let { view ->
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }
    private var closing = false
    private var entered = false
    private var originX = 0f
    private var originY = 0f
    private var originScaleX = .97f
    private var originScaleY = .97f
    lateinit var surface: View

    fun animateEntrance() {
        surface.alpha = 0f
        surface.post {
            if (!isShowing || closing) return@post
            val location = IntArray(2)
            surface.getLocationOnScreen(location)
            sourceBounds?.let {
                originX = (it.left - location[0]).toFloat()
                originY = (it.top - location[1]).toFloat()
                originScaleX = it.width().toFloat() / surface.width.coerceAtLeast(1)
                originScaleY = it.height().toFloat() / surface.height.coerceAtLeast(1)
            }
            surface.pivotX = 0f; surface.pivotY = 0f
            surface.translationX = originX; surface.translationY = originY
            surface.scaleX = originScaleX; surface.scaleY = originScaleY
            entered = true
            surface.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(270).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (closing) true else super.dispatchTouchEvent(event)

    override fun dismiss() {
        if (closing) return
        if (!isShowing || !entered || !surface.isAttachedToWindow) { super.dismiss(); return }
        closing = true
        surface.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(surface.windowToken, 0)
        surface.animate().cancel()
        surface.animate().alpha(0f).scaleX(originScaleX).scaleY(originScaleY)
            .translationX(originX).translationY(originY).setDuration(190)
            .setInterpolator(DecelerateInterpolator()).withEndAction { dismissImmediately() }.start()
    }

    fun dismissImmediately() {
        closing = true
        if (::surface.isInitialized) surface.animate().cancel()
        super.dismiss()
    }
}
