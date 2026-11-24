package com.authnotes.ui.home

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.authnotes.R
import com.authnotes.ui.EditorViews

/** Owns the shared drawer surface and its animated open/close lifecycle. */
internal class DrawerController(
    private val activity: Activity,
    private val onNavigate: (Int) -> Unit,
    private val onHeaderChanged: (Boolean) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit,
) : EditorViews(activity) {
    var overlay: FrameLayout? = null
        private set
    var isClosing = false
        private set
    val panel: View? get() = overlay?.getChildAt(1)
    val shade: View? get() = overlay?.getChildAt(0)
    val width: Int get() = panel?.layoutParams?.width ?: 0
    val translationX: Float get() = panel?.translationX ?: -width.toFloat()

    fun show(root: FrameLayout, initialDrag: Float = 0f) {
        if (overlay != null) { close(); return }
        onHeaderChanged(true)
        val shownOverlay = FrameLayout(activity)
        val shownShade = View(activity).apply {
            setBackgroundColor(0x33202624); alpha = 0f; setOnClickListener { close() }
        }
        shownOverlay.addView(shownShade, FrameLayout.LayoutParams(-1, -1))
        val shownPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE); elevation = dp(10).toFloat(); pad(20); isClickable = true
        }
        val panelWidth = minOf(dp(310), activity.resources.displayMetrics.widthPixels - dp(44))
        shownOverlay.addView(shownPanel, FrameLayout.LayoutParams(panelWidth, -1, Gravity.START))
        shownPanel.addView(text("Auth Notes", 25, true).apply { setPadding(dp(8), dp(32), 0, dp(28)) })
        val entries = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        shownPanel.addView(ScrollView(activity).apply { isFillViewport = true; addView(entries) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        val labels = listOf("Account Info", "Authenticator", "认证账户迁移", "文本解析", "导出备份", "导入恢复", "设置", "关于")
        val icons = listOf(R.drawable.nav_account, R.drawable.nav_authenticator, R.drawable.nav_authenticator,
            R.drawable.nav_parser, R.drawable.nav_export, R.drawable.nav_import, R.drawable.nav_settings, R.drawable.nav_about)
        labels.forEachIndexed { index, label ->
            val item = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; alpha = 0f; translationX = -dp(10).toFloat(); isFocusable = true
                val value = android.util.TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                setBackgroundResource(value.resourceId)
                setPadding(dp(8), 0, dp(8), 0)
                contentDescription = label
            }
            item.addView(ImageView(activity).apply {
                setImageResource(icons[index]); setColorFilter(DRAWER_TEXT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(18) })
            item.addView(text(label, 16, color = DRAWER_TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            val container = if (index == 7) shownPanel else entries
            if (index == 7) container.addView(View(activity).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
            container.addView(item, LinearLayout.LayoutParams(-1, dp(56)))
            if (index < 6) container.addView(View(activity).apply { setBackgroundColor(RULE) }, LinearLayout.LayoutParams(-1, dp(1)))
            item.setOnClickListener { close { onNavigate(index) } }
            item.animate().alpha(1f).translationX(0f).setStartDelay(index * 18L).setDuration(210).start()
        }
        overlay = shownOverlay
        isClosing = false
        root.addView(shownOverlay, FrameLayout.LayoutParams(-1, -1))
        shownPanel.translationX = if (initialDrag == 0f) -panelWidth.toFloat() else (initialDrag - panelWidth).coerceAtMost(0f)
        if (initialDrag == 0f) animateOpen() else updateDrag(initialDrag - panelWidth)
        onVisibilityChanged(true)
    }

    fun animateOpen() {
        val shownPanel = panel ?: return
        shade?.animate()?.alpha(1f)?.setDuration(220)?.start()
        shownPanel.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    fun updateDrag(translation: Float) {
        val shownOverlay = overlay ?: return
        val shownPanel = panel ?: return
        val width = shownPanel.layoutParams.width.coerceAtLeast(1).toFloat()
        shownPanel.translationX = translation.coerceIn(-width, 0f)
        shownOverlay.getChildAt(0).apply {
            animate().cancel()
            alpha = 1f + shownPanel.translationX / width
        }
    }

    fun close(afterClosed: (() -> Unit)? = null) {
        val shownOverlay = overlay ?: return
        if (isClosing) return
        isClosing = true
        onHeaderChanged(false)
        shade?.animate()?.alpha(0f)?.setDuration(180)?.start()
        fun fadeChildren(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is android.widget.TextView) child.animate().alpha(0f).translationX(-dp(7).toFloat()).setDuration(140).start()
                if (child is ViewGroup) fadeChildren(child)
            }
        }
        (panel as? ViewGroup)?.let(::fadeChildren)
        panel?.animate()?.translationX(-width.toFloat())?.setDuration(210)?.setInterpolator(DecelerateInterpolator())?.withEndAction {
            if (overlay === shownOverlay) {
                (shownOverlay.parent as? ViewGroup)?.removeView(shownOverlay)
                overlay = null; isClosing = false
                onVisibilityChanged(false)
                afterClosed?.invoke()
            }
        }?.start()
    }

    fun dismissImmediately() {
        val shownOverlay = overlay ?: return
        panel?.animate()?.cancel(); shade?.animate()?.cancel()
        (shownOverlay.parent as? ViewGroup)?.removeView(shownOverlay)
        overlay = null; isClosing = false
        onVisibilityChanged(false)
    }
}
