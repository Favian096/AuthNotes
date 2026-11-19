package com.authnotes.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout

/** Vertical reordering with the real row under the finger and animated space for its neighbors. */
class ReorderColumn(context: Context) : LinearLayout(context) {
    var onOrderChanged: (() -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null
    var onEdgeDrag: ((Float) -> Unit)? = null
    private var moving: View? = null
    private var pointerY = 0f
    private var fingerOffset = 0f
    private var centerY = 0f
    private var original = emptyList<View>()
    private var oldElevation = 0f
    private var swapPending = false
    private var captured = false
    private var cancellingChild = false
    private val position = IntArray(2)
    val isReordering get() = moving != null
    private val dragFrame = object : Runnable {
        override fun run() {
            if (moving == null) return
            onEdgeDrag?.invoke(pointerY)
            positionRow(); swapIfNeeded()
            postOnAnimation(this)
        }
    }

    init { orientation = VERTICAL; clipChildren = false; clipToPadding = false }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!cancellingChild && moving != null && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (!captured) {
                captured = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                // Clear the old child target without treating its cancel as a canceled drag.
                cancellingChild = true
                super.dispatchTouchEvent(cancel)
                cancellingChild = false
                cancel.recycle()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return onTouchEvent(event)
        }
        if (!cancellingChild) pointerY = event.rawY
        return super.dispatchTouchEvent(event)
    }

    fun begin(row: View, rawY: Float = pointerY): Boolean {
        if (moving != null || row.parent !== this) return false
        row.animate().cancel()
        original = (0 until childCount).map { getChildAt(it) }
        moving = row; captured = false
        pointerY = rawY
        centerY = row.top + row.height / 2f
        getLocationOnScreen(position)
        fingerOffset = rawY - position[1] - centerY
        oldElevation = row.elevation
        row.elevation = 12f * resources.displayMetrics.density
        row.alpha = .94f
        parent?.requestDisallowInterceptTouchEvent(true)
        onDragStateChanged?.invoke(true)
        removeCallbacks(dragFrame); postOnAnimation(dragFrame)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cancellingChild) return true
        if (moving == null) return super.onTouchEvent(event)
        pointerY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { positionRow(); swapIfNeeded() }
            MotionEvent.ACTION_UP -> finish(false)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finish(true)
        }
        return true
    }

    private fun positionRow() {
        val row = moving ?: return
        getLocationOnScreen(position)
        // Screen coordinates also account for the containing ScrollView's movement.
        centerY = pointerY - position[1] - fingerOffset
        row.translationY = centerY - row.top - row.height / 2f
    }

    private fun swapIfNeeded() {
        val row = moving ?: return
        if (swapPending) return
        val current = indexOfChild(row)
        val before = if (current > 0) getChildAt(current - 1) else null
        val after = if (current < childCount - 1) getChildAt(current + 1) else null
        val target = when {
            before != null && centerY < before.top + before.height / 2f -> current - 1
            after != null && centerY > after.top + after.height / 2f -> current + 1
            else -> return
        }
        val oldTops = (0 until childCount).associate { getChildAt(it) to (getChildAt(it).top + getChildAt(it).translationY) }
        swapPending = true
        removeView(row); addView(row, target)
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
                swapPending = false
                if (moving === row) {
                    for ((child, oldTop) in oldTops) if (child !== row && child.parent === this@ReorderColumn) {
                        child.animate().cancel(); child.translationY = oldTop - child.top
                        child.animate().translationY(0f).setDuration(170).setInterpolator(DecelerateInterpolator()).start()
                    }
                    positionRow()
                    postOnAnimation { if (moving === row) swapIfNeeded() }
                }
                return true
            }
        })
    }

    fun finish(cancel: Boolean) {
        val row = moving ?: return
        moving = null; captured = false
        removeCallbacks(dragFrame)
        if (cancel) {
            removeAllViews(); original.forEach { addView(it) }
        }
        val restingElevation = oldElevation
        row.animate().translationY(0f).alpha(1f).setDuration(170).withEndAction { row.elevation = restingElevation }.start()
        parent?.requestDisallowInterceptTouchEvent(false)
        onDragStateChanged?.invoke(false)
        if (!cancel) onOrderChanged?.invoke()
        original = emptyList()
    }

    override fun onDetachedFromWindow() {
        finish(true)
        super.onDetachedFromWindow()
    }
}
