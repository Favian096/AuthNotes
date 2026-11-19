package com.authnotes.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/** Shared smooth-corner background used by cards, panels, and floating surfaces. */
class SmoothDrawable(
    private val fill: Int,
    private val density: Float,
    private val radiusDp: Float = 22f,
    private val strokeColor: Int = Color.TRANSPARENT,
    private val strokeDp: Float = 0f,
    private val roundTop: Boolean = true,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        buildPath()
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.alpha = (Color.alpha(fill) * (drawableAlpha / 255f)).toInt()
        canvas.drawPath(path, paint)
        if (strokeDp > 0f && Color.alpha(strokeColor) > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeDp * density
            paint.color = strokeColor
            paint.alpha = (Color.alpha(strokeColor) * (drawableAlpha / 255f)).toInt()
            canvas.drawPath(path, paint)
        }
    }

    override fun getOutline(outline: Outline) {
        buildPath()
        outline.setPath(path)
    }

    private fun buildPath() {
        rect.set(bounds)
        val radius = min(radiusDp * density, min(rect.width(), rect.height()) / 2f)
        smoothCornerPath(path, rect, radius, roundTop)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Continuous curvature at the joins between straight edges and the corner arcs. */
private fun smoothCornerPath(path: Path, rect: RectF, radius: Float, roundTop: Boolean = true) {
    path.reset()
    val topRadius = if (roundTop) radius else 0f
    path.moveTo(rect.left + topRadius, rect.top)
    path.lineTo(rect.right - topRadius, rect.top)
    // Two cubics per corner: zero curvature at both straight-edge joins,
    // matching first and second derivatives at the diagonal join.
    path.cubicTo(rect.right - topRadius * .72f, rect.top, rect.right - topRadius * .36f, rect.top, rect.right - topRadius * .18f, rect.top + topRadius * .18f)
    path.cubicTo(rect.right, rect.top + topRadius * .36f, rect.right, rect.top + topRadius * .72f, rect.right, rect.top + topRadius)
    path.lineTo(rect.right, rect.bottom - radius)
    path.cubicTo(rect.right, rect.bottom - radius * .72f, rect.right, rect.bottom - radius * .36f, rect.right - radius * .18f, rect.bottom - radius * .18f)
    path.cubicTo(rect.right - radius * .36f, rect.bottom, rect.right - radius * .72f, rect.bottom, rect.right - radius, rect.bottom)
    path.lineTo(rect.left + radius, rect.bottom)
    path.cubicTo(rect.left + radius * .72f, rect.bottom, rect.left + radius * .36f, rect.bottom, rect.left + radius * .18f, rect.bottom - radius * .18f)
    path.cubicTo(rect.left, rect.bottom - radius * .36f, rect.left, rect.bottom - radius * .72f, rect.left, rect.bottom - radius)
    path.lineTo(rect.left, rect.top + topRadius)
    path.cubicTo(rect.left, rect.top + topRadius * .72f, rect.left, rect.top + topRadius * .36f, rect.left + topRadius * .18f, rect.top + topRadius * .18f)
    path.cubicTo(rect.left + topRadius * .36f, rect.top, rect.left + topRadius * .72f, rect.top, rect.left + topRadius, rect.top)
    path.close()
}

class UnderlineDrawable(
    private val density: Float,
    private val inactive: Int,
    private val active: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var focus = false
    private var lineAlpha = 255
    var reveal: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidateSelf() }
    fun setFocused(value: Boolean) { focus = value; invalidateSelf() }
    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = if (focus) active else inactive
        paint.strokeWidth = (if (focus) 2f else 1.4f) * density
        paint.alpha = lineAlpha
        canvas.drawLine(b.right - b.width() * reveal, b.bottom - paint.strokeWidth, b.right.toFloat(), b.bottom - paint.strokeWidth, paint)
    }
    override fun setAlpha(alpha: Int) { lineAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

class ColorDotView(context: android.content.Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var halo: android.graphics.RadialGradient? = null
    private var emphasis = 1f
    private var emphasisAnimator: ValueAnimator? = null
    fun emphasize(value: Boolean) {
        emphasisAnimator?.cancel()
        emphasisAnimator = ValueAnimator.ofFloat(emphasis, if (value) 1.7f else 1f).apply {
            duration = 190; interpolator = DecelerateInterpolator()
            addUpdateListener { emphasis = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        halo = android.graphics.RadialGradient(w / 2f, h / 2f, 10f * density,
            intArrayOf((color and 0x00ffffff) or 0x60000000, (color and 0x00ffffff) or 0x24000000, color and 0x00ffffff),
            floatArrayOf(0f, .42f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        paint.shader = halo; paint.alpha = 255
        canvas.drawCircle(cx, cy, 10f * density * emphasis, paint)
        paint.shader = null; paint.color = color
        canvas.drawCircle(cx, cy, 3.5f * density, paint)
    }
}

class ColorStripView(
    context: android.content.Context,
    private val colors: IntArray,
    initial: Int,
    private val changed: (Int) -> Unit,
) : View(context) {
    var onGesture: ((Boolean) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var selected = colors.indices.minByOrNull { index ->
        val color = colors[index]
        val dr = Color.red(color) - Color.red(initial)
        val dg = Color.green(color) - Color.green(initial)
        val db = Color.blue(color) - Color.blue(initial)
        dr * dr + dg * dg + db * db
    } ?: 0
    init { isClickable = true; contentDescription = "拖动选择颜色" }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (58f * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 3f * density
        val track = RectF(inset, height * .25f, width - inset, height * .75f)
        val segment = track.width() / colors.size
        val gradientColors = colors
        val stops = FloatArray(colors.size) { (it.toFloat() / (colors.size - 1)).coerceIn(0f, 1f) }
        val clip = Path().apply { smoothCornerPath(this, track, track.height() / 2f) }
        canvas.save(); canvas.clipPath(clip)
        paint.shader = android.graphics.LinearGradient(track.left, 0f, track.right, 0f, gradientColors, stops, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(track, paint)
        paint.shader = null
        canvas.restore()
        paint.color = colors[selected]; paint.style = Paint.Style.FILL
        val x = track.left + segment * (selected + .5f)
        val marker = RectF(x - 5f * density, track.top - 5f * density, x + 5f * density, track.bottom + 5f * density)
        canvas.drawRoundRect(marker, 5f * density, 5f * density, paint)
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && !isEnabled) return false
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onGesture?.invoke(true)
                }
                val inset = 3f * density
                val next = (((event.x - inset) / (width - inset * 2f) * colors.size).toInt()).coerceIn(colors.indices)
                if (next != selected) { selected = next; changed(colors[selected]); invalidate() }
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onGesture?.invoke(false)
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

class EncryptedDatabaseIconView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val density = resources.displayMetrics.density
    private var fillProgress = 0f
    private var animator: ValueAnimator? = null
    fun setLoaded(value: Boolean, animate: Boolean = true) {
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate) { fillProgress = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(fillProgress, target).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { fillProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
        val w = s * .60f; val h = s * .68f; val left = cx - w / 2f; val top = cy - h / 2f
        val document = Path().apply { smoothCornerPath(this, RectF(left, top, left + w, top + h), 8f * density) }
        val color = Color.rgb(110, 145, 132)
        paint.strokeWidth = 2f * density
        paint.color = color
        paint.alpha = (255 * (1f - fillProgress * .78f)).toInt()
        canvas.drawPath(document, paint)
        paint.alpha = (255 * fillProgress).toInt(); paint.style = Paint.Style.FILL
        canvas.drawPath(document, paint)
        paint.color = blend(color, Color.WHITE, fillProgress); paint.alpha = 255
        val y1 = top + h * .32f; val y2 = top + h * .50f; val y3 = top + h * .68f
        canvas.drawLine(left + w * .20f, y1, left + w * .80f, y1, paint)
        canvas.drawLine(left + w * .20f, y2, left + w * .80f, y2, paint)
        canvas.drawLine(left + w * .20f, y3, left + w * .60f, y3, paint)
        paint.style = Paint.Style.STROKE
        val lock = RectF(left + w * .60f, top + h * .58f, left + w * .95f, top + h * .88f)
        canvas.drawRoundRect(lock, 3f * density, 3f * density, paint)
        canvas.drawArc(RectF(lock.left + lock.width() * .2f, lock.top - lock.height() * .48f, lock.right - lock.width() * .2f, lock.top + lock.height() * .24f), 180f, 180f, false, paint)
    }

    private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
    )
}

class ActionIconView(context: android.content.Context, private val keyMode: Boolean, private val tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val density = resources.displayMetrics.density
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat() * .72f
        val x = (width - s) / 2f; val y = (height - s) / 2f
        paint.strokeWidth = 2f * density
        if (!keyMode) {
            val cell = s / 3f
            for ((col, row) in listOf(0 to 0, 2 to 0, 0 to 2)) {
                val l = x + col * cell; val t = y + row * cell
                canvas.drawRect(l, t, l + cell * .88f, t + cell * .88f, paint)
                canvas.drawRect(l + cell * .22f, t + cell * .22f, l + cell * .66f, t + cell * .66f, paint)
            }
            canvas.drawRect(x + cell * 1.3f, y + cell * 1.25f, x + cell * 1.65f, y + cell * 1.6f, paint)
            canvas.drawRect(x + cell * 2.1f, y + cell * 1.8f, x + cell * 2.45f, y + cell * 2.15f, paint)
        } else {
            canvas.drawCircle(x + s * .30f, y + s * .48f, s * .17f, paint)
            path.reset(); path.moveTo(x + s * .44f, y + s * .59f); path.lineTo(x + s * .85f, y + s * .18f)
            path.moveTo(x + s * .68f, y + s * .35f); path.lineTo(x + s * .80f, y + s * .47f)
            path.moveTo(x + s * .57f, y + s * .46f); path.lineTo(x + s * .69f, y + s * .58f)
            canvas.drawPath(path, paint)
        }
    }
}

class MorphIconView(
    context: android.content.Context,
    private val tint: Int,
    private val from: Mode,
    private val to: Mode,
) : View(context) {
    enum class Mode { MENU, SEARCH, CLOSE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var progress = 0f
    private val path = Path()
    private val animator get() = ValueAnimator.ofFloat(progress, 1f).apply {
        duration = 190
        interpolator = DecelerateInterpolator()
        addUpdateListener { progress = it.animatedValue as Float; invalidate() }
    }
    private var running: ValueAnimator? = null

    init { contentDescription = "图标按钮" }

    fun setProgress(value: Float) { progress = value.coerceIn(0f, 1f); invalidate() }
    fun animateTo(value: Float) { if (value >= .5f) animateToEnd() else animateToStart() }
    fun animateToEnd() { running?.cancel(); running = animator.also { it.start() } }
    fun animateToStart() {
        running?.cancel()
        running = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = 190
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        val inset = side * 0.27f
        val left = (width - side) / 2f + inset
        val top = (height - side) / 2f + inset
        val right = (width + side) / 2f - inset
        val bottom = (height + side) / 2f - inset
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val t = progress
        path.reset()
        if (from == Mode.MENU) {
            path.moveTo(left, top); path.lineTo(right, mix(top, bottom, t))
            path.moveTo(left, bottom); path.lineTo(right, mix(bottom, top, t))
            canvas.drawPath(path, paint)
            paint.alpha = (255 * (1f - t)).toInt()
            canvas.drawLine(left, cy, right, cy, paint)
            paint.alpha = 255
            return
        }
        if (from == Mode.SEARCH) {
            val radius = (right - left) * .34f
            val circleX = left + radius
            val circleY = top + radius
            // Deform the sampled lens contour into the rising stroke of the cross.
            for (i in 0..48) {
                val angle = i * Math.PI * 2.0 / 48
                val x = circleX + radius * kotlin.math.cos(angle).toFloat()
                val y = circleY + radius * kotlin.math.sin(angle).toFloat()
                val linePosition = kotlin.math.cos(angle + Math.PI / 4).toFloat()
                val targetX = cx + linePosition * (right - left) * .43f
                val targetY = cy - linePosition * (bottom - top) * .43f
                if (i == 0) path.moveTo(mix(x, targetX, t), mix(y, targetY, t))
                else path.lineTo(mix(x, targetX, t), mix(y, targetY, t))
            }
            path.moveTo(mix(circleX + radius * .71f, left, t), mix(circleY + radius * .71f, top, t))
            path.lineTo(right, bottom)
            canvas.drawPath(path, paint)
            return
        }
        canvas.drawLine(left, top, right, bottom, paint)
        canvas.drawLine(left, bottom, right, top, paint)
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
}
