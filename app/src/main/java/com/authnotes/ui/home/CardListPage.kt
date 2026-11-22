package com.authnotes.ui.home

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.authnotes.R
import com.authnotes.ui.ColorDotView
import com.authnotes.ui.ReorderColumn
import com.authnotes.ui.SmoothDrawable

internal abstract class CardListPage(
    protected val context: Context,
    parent: ViewGroup,
    private val onDragChanged: (Boolean) -> Unit,
    private val onOrderChanged: (List<Long>) -> Unit,
    protected val notify: (String) -> Unit,
) {
    private val inflater = LayoutInflater.from(context)
    val view = inflater.inflate(R.layout.view_home_list, parent, false) as ScrollView
    private val rows = view.findViewById<ReorderColumn>(R.id.card_rows)
    private var draggedRow: View? = null
    private var renderedQuery: String? = null
    val isLoaded get() = renderedQuery != null

    init {
        view.isSaveFromParentEnabled = false
        rows.onDragStateChanged = { active ->
            onDragChanged(active)
            draggedRow?.let { row ->
                (row.getTag(R.id.drag_dot) as? ColorDotView)?.emphasize(active)
                row.elevation = dp(if (active) 14 else 3).toFloat()
                val shadow = if (active) row.getTag(R.id.drag_color) as Int else Color.LTGRAY
                row.outlineAmbientShadowColor = shadow
                row.outlineSpotShadowColor = shadow
            }
            if (!active) draggedRow = null
        }
        rows.onOrderChanged = {
            val ids = (0 until rows.childCount).mapNotNull { rows.getChildAt(it).tag as? Long }
            runCatching { onOrderChanged(ids) }
                .onFailure { notify(it.message ?: context.getString(R.string.card_reorder_failed)) }
        }
        rows.onEdgeDrag = { rawY ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val edge = dp(56)
            val top = location[1] + edge
            val bottom = location[1] + view.height - edge
            val distance = when {
                rawY < top -> -((top - rawY) / edge).coerceIn(0f, 1f)
                rawY > bottom -> ((rawY - bottom) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            view.scrollBy(0, (distance * dp(5)).toInt())
        }
    }

    fun refresh(query: String, animate: Boolean) {
        rows.finish(true)
        rows.removeAllViews()
        renderedQuery = query
        bindRows(query, animate)
    }

    protected abstract fun bindRows(query: String, animate: Boolean)

    protected fun showEmpty(message: Int) {
        rows.addView(TextView(context).apply {
            setText(message)
            setTextColor(context.getColor(R.color.text_muted))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(72), 0, dp(72))
        })
    }

    protected fun inflateCard(layout: Int, id: Long, color: Int, onOpen: (View) -> Unit): View =
        inflater.inflate(layout, rows, false).apply {
            background = surface(Color.WHITE, 18f)
            val dot = ColorDotView(context, color)
            findViewById<FrameLayout>(R.id.card_color_slot).addView(dot, FrameLayout.LayoutParams(-1, -1))
            tag = id
            setTag(R.id.drag_dot, dot)
            setTag(R.id.drag_color, color)
            outlineAmbientShadowColor = Color.LTGRAY
            outlineSpotShadowColor = Color.LTGRAY
            setOnClickListener { onOpen(it) }
            setOnLongClickListener {
                if (!renderedQuery.isNullOrEmpty()) notify(context.getString(R.string.card_reorder_clear_search))
                else {
                    draggedRow = this
                    rows.begin(this)
                }
                true
            }
        }

    protected fun addCard(row: View, index: Int, animate: Boolean) {
        rows.addView(row)
        if (!animate) return
        row.post {
            if (row.parent !== rows || view.visibility != View.VISIBLE) return@post
            val location = IntArray(2)
            row.getLocationOnScreen(location)
            if (location[1] < context.resources.displayMetrics.heightPixels) {
                row.alpha = 0f
                row.translationY = dp(14).toFloat()
                row.animate().alpha(1f).translationY(0f).setStartDelay((8 - index.coerceAtMost(8)) * 18L)
                    .setDuration(260).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    protected fun surface(color: Int, radius: Float) =
        SmoothDrawable(color, context.resources.displayMetrics.density, radius)

    protected fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
}
