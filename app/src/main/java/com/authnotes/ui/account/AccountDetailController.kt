package com.authnotes.ui.account

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.PersistableBundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.authnotes.R
import com.authnotes.data.AccountCard
import com.authnotes.data.AccountRepository
import com.authnotes.ui.EditorViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns one account detail/edit surface. Window security and backing-list effects belong to the host. */
internal class AccountDetailController(
    private val activity: Activity,
    private val accounts: AccountRepository,
    private val attachments: AttachmentController,
    private val createEditor: (AccountCard) -> AccountEditor,
    private val notify: (String) -> Unit,
    private val onChanged: () -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit,
) : EditorViews(activity) {
    private var root: FrameLayout? = null
    private var overlay: FrameLayout? = null
    private var surface: LinearLayout? = null
    private var sourceRect: Rect? = null
    private var closing = false
    private val protectedFields = mutableListOf<TextView>()
    val isShowing get() = overlay != null

    fun show(parent: FrameLayout, card: AccountCard, source: View) {
        if (isShowing) return
        root = parent
        closing = false
        val position = IntArray(2); source.getLocationOnScreen(position)
        val rootPosition = IntArray(2); parent.getLocationOnScreen(rootPosition)
        sourceRect = Rect(position[0] - rootPosition[0], position[1] - rootPosition[1],
            position[0] - rootPosition[0] + source.width, position[1] - rootPosition[1] + source.height)
        val shownOverlay = FrameLayout(activity).apply { alpha = 0f }
        shownOverlay.addView(View(activity).apply {
            setBackgroundColor(0x4d202624)
            setOnClickListener { close() }
        }, FrameLayout.LayoutParams(-1, -1))
        val shownSurface = activity.layoutInflater.inflate(R.layout.view_account_surface, shownOverlay, false) as LinearLayout
        shownSurface.background = smooth(FLOATING, 26f, RULE, .6f)
        val bodyScroll = shownSurface.findViewById<ScrollView>(R.id.account_surface_scroll)
        val body = activity.layoutInflater.inflate(R.layout.view_account_detail, bodyScroll, false) as LinearLayout
        body.findViewById<TextView>(R.id.account_detail_id).text = "序号: ${card.id}"
        body.findViewById<TextView>(R.id.account_detail_time).text = "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(card.updateTime))}"
        body.findViewById<View>(R.id.account_detail_color).setBackgroundColor(card.color)
        body.findViewById<TextView>(R.id.account_detail_tag).text = "标签: ${card.tag}"
        body.findViewById<TextView>(R.id.account_detail_remark).text = "备注: ${card.remark}"
        bindFields(card.id, body.findViewById(R.id.account_detail_fields))
        bodyScroll.addView(body)
        shownSurface.findViewById<View>(R.id.account_surface_close).setOnClickListener { close() }
        shownSurface.findViewById<TextView>(R.id.account_surface_primary).apply {
            setOnClickListener {
                if (tag == "confirm") {
                    accounts.delete(card.id)
                    close(onChanged)
                } else {
                    tag = "confirm"; text = "确认删除"; setTextColor(0xffd70015.toInt())
                    animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    }.start()
                }
            }
        }
        shownSurface.findViewById<View>(R.id.account_surface_secondary).setOnClickListener { edit(card) }
        val height = minOf(dp(620), (parent.height - dp(48)).coerceAtLeast(dp(120)))
        shownOverlay.addView(shownSurface, FrameLayout.LayoutParams(-1, height, Gravity.CENTER).apply {
            leftMargin = dp(18); rightMargin = dp(18)
        })
        overlay = shownOverlay; surface = shownSurface
        // Secure the host before the sensitive surface becomes visible.
        onVisibilityChanged(true)
        parent.addView(shownOverlay, FrameLayout.LayoutParams(-1, -1))
        val section = body.findViewById<LinearLayout>(R.id.account_detail_attachments)
        shownSurface.post {
            val origin = sourceRect ?: return@post
            if (overlay !== shownOverlay || closing) return@post
            shownSurface.pivotX = 0f; shownSurface.pivotY = 0f
            shownSurface.scaleX = origin.width().toFloat() / shownSurface.width.coerceAtLeast(1)
            shownSurface.scaleY = origin.height().toFloat() / shownSurface.height.coerceAtLeast(1)
            shownSurface.translationX = origin.left - (parent.width - shownSurface.width) / 2f
            shownSurface.translationY = origin.top - (parent.height - shownSurface.height) / 2f
            shownOverlay.animate().alpha(1f).setDuration(190).start()
            shownSurface.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(280).setInterpolator(DecelerateInterpolator()).withEndAction {
                    if (overlay === shownOverlay && !closing && section.isAttachedToWindow) {
                        attachments.load(card.id, section) {
                            overlay === shownOverlay && !closing && section.isAttachedToWindow
                        }
                    }
                }.start()
        }
    }

    private fun bindFields(cardId: Long, container: LinearLayout) {
        accounts.fields(cardId).forEach { field ->
            val row = activity.layoutInflater.inflate(R.layout.item_account_detail_field, container, false)
            row.findViewById<TextView>(R.id.detail_field_name).text = "${field.name}:"
            val value = row.findViewById<TextView>(R.id.detail_field_value).apply {
                var pressedAt = 0L
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { pressedAt = event.eventTime; text = field.value }
                        MotionEvent.ACTION_UP -> {
                            text = "••••••"
                            if (event.eventTime - pressedAt < ViewConfiguration.getLongPressTimeout()) view.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> text = "••••••"
                    }
                    true
                }
                setOnClickListener {
                    val clip = ClipData.newPlainText(field.name, field.value)
                    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    notify("${field.name} 已复制")
                }
            }
            protectedFields += value
            container.addView(row)
        }
    }

    private fun edit(card: AccountCard) {
        val shownSurface = surface ?: return
        if (closing) return
        concealFields()
        shownSurface.animate().cancel()
        shownSurface.animate().alpha(.45f).setDuration(100).withEndAction {
            if (surface !== shownSurface || closing) return@withEndAction
            protectedFields.clear()
            val editor = createEditor(card)
            shownSurface.findViewById<TextView>(R.id.account_surface_title).setText(R.string.account_edit_title)
            shownSurface.findViewById<ScrollView>(R.id.account_surface_scroll).apply {
                removeAllViews(); addView(editor.body); scrollTo(0, 0)
            }
            shownSurface.findViewById<TextView>(R.id.account_surface_primary).apply {
                animate().cancel(); scaleX = 1f; scaleY = 1f; tag = null
                setText(R.string.editor_cancel); setTextColor(MUTED)
                setOnClickListener { close() }
            }
            shownSurface.findViewById<TextView>(R.id.account_surface_secondary).apply {
                setText(R.string.editor_save)
                setOnClickListener { editor.save { close(onChanged) } }
            }
            shownSurface.alpha = 0f
            shownSurface.animate().alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    fun concealFields() { protectedFields.forEach { it.text = "••••••" } }

    fun close(afterClosed: (() -> Unit)? = null) {
        val shownOverlay = overlay ?: return
        val shownSurface = surface ?: return
        val parent = root ?: return
        if (closing) return
        closing = true
        concealFields()
        shownSurface.clearFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(shownSurface.windowToken, 0)
        val origin = sourceRect
        shownSurface.animate().cancel()
        shownSurface.animate()
            .scaleX(if (origin != null) origin.width().toFloat() / shownSurface.width.coerceAtLeast(1) else .9f)
            .scaleY(if (origin != null) origin.height().toFloat() / shownSurface.height.coerceAtLeast(1) else .9f)
            .translationX(if (origin != null) origin.left - (parent.width - shownSurface.width) / 2f else 0f)
            .translationY(if (origin != null) origin.top - (parent.height - shownSurface.height) / 2f else 0f)
            .setDuration(190).setInterpolator(DecelerateInterpolator()).withEndAction {
                if (overlay === shownOverlay) { dismissImmediately(); afterClosed?.invoke() }
            }.start()
        shownOverlay.animate().alpha(0f).setDuration(180).start()
    }

    fun dismissImmediately() {
        val shownOverlay = overlay ?: return
        concealFields()
        overlay?.animate()?.cancel(); surface?.animate()?.cancel()
        root?.removeView(shownOverlay)
        overlay = null; surface = null; root = null; sourceRect = null; closing = false
        protectedFields.clear()
        onVisibilityChanged(false)
    }
}
