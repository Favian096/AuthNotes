package com.authnotes.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.atan2
import kotlin.math.min

class ZoomRotateImageView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private var lastX = 0f
    private var lastY = 0f
    private var lastDistance = 0f
    private var lastAngle = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var relativeScale = 1f

    init { scaleType = ScaleType.MATRIX; isClickable = true }

    override fun setImageBitmap(bitmap: Bitmap?) {
        super.setImageBitmap(bitmap)
        relativeScale = 1f
        post { fitImage() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImage()
    }

    private fun fitImage() {
        val image = drawable ?: return
        if (width <= 0 || height <= 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        val scale = min(width.toFloat() / image.intrinsicWidth, height.toFloat() / image.intrinsicHeight)
        val left = (width - image.intrinsicWidth * scale) / 2f
        val top = (height - image.intrinsicHeight * scale) / 2f
        transform.setScale(scale, scale)
        transform.postTranslate(left, top)
        imageMatrix = transform
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) resetPair(event)
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val x = (event.getX(0) + event.getX(1)) / 2f
                    val y = (event.getY(0) + event.getY(1)) / 2f
                    val distance = pairDistance(event)
                    if (lastDistance > 0f && distance > 0f) {
                        val rawFactor = (distance / lastDistance).coerceIn(.85f, 1.18f)
                        val nextScale = (relativeScale * rawFactor).coerceIn(.5f, 8f)
                        val factor = nextScale / relativeScale
                        relativeScale = nextScale
                        transform.postScale(factor, factor, x, y)
                        var rotation = pairAngle(event) - lastAngle
                        if (rotation > 180f) rotation -= 360f
                        if (rotation < -180f) rotation += 360f
                        transform.postRotate(rotation, x, y)
                        transform.postTranslate(x - lastFocusX, y - lastFocusY)
                        imageMatrix = transform
                    }
                    lastDistance = distance; lastAngle = pairAngle(event)
                    lastFocusX = x; lastFocusY = y
                } else {
                    transform.postTranslate(event.x - lastX, event.y - lastY)
                    imageMatrix = transform
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount > 2) resetPair(event, event.actionIndex)
                else lastDistance = 0f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastDistance = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    private fun resetPair(event: MotionEvent, skipIndex: Int = -1) {
        val pointers = (0 until event.pointerCount).filter { it != skipIndex }
        if (pointers.size < 2) return
        val first = pointers[0]
        val second = pointers[1]
        lastDistance = distance(event.getX(first), event.getY(first), event.getX(second), event.getY(second))
        lastAngle = pairAngle(event, first, second)
        lastFocusX = (event.getX(first) + event.getX(second)) / 2f
        lastFocusY = (event.getY(first) + event.getY(second)) / 2f
    }

    private fun pairDistance(event: MotionEvent) = distance(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float) = Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
    private fun pairAngle(event: MotionEvent) = pairAngle(event, 0, 1)
    private fun pairAngle(event: MotionEvent, first: Int, second: Int) = Math.toDegrees(
        atan2((event.getY(second) - event.getY(first)).toDouble(), (event.getX(second) - event.getX(first)).toDouble())
    ).toFloat()

    override fun performClick(): Boolean = super.performClick()
}
