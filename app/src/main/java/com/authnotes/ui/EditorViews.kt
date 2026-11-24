package com.authnotes.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.authnotes.R

/** Shared native View styling for editors and detail surfaces; contains no page state. */
internal open class EditorViews(protected val context: Context) {
    protected val RULE = context.getColor(R.color.rule)
    protected val SURFACE = context.getColor(R.color.surface)
    protected val FOREGROUND = context.getColor(R.color.text_primary)
    protected val MUTED = context.getColor(R.color.text_muted)
    protected val BLUE = context.getColor(R.color.accent_blue)
    protected val FLOATING = context.getColor(R.color.floating_surface)
    protected val DRAWER_TEXT = context.getColor(R.color.drawer_text)

    protected fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
    protected fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    protected fun View.pad(value: Int) { val px = dp(value); setPadding(px, px, px, px) }
    protected fun text(value: String, size: Int = 15, bold: Boolean = false, color: Int = FOREGROUND) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    protected fun configureInput(field: EditText, hintValue: String? = null) = with(field) {
        if (hintValue != null) hint = hintValue
        textSize = 18f; setTextColor(FOREGROUND); setHintTextColor(MUTED)
        setPadding(dp(8), dp(10), dp(8), dp(8))
        val line = underline()
        background = line
        setOnFocusChangeListener { _, focused -> line.setFocused(focused) }
        textCursorDrawable = context.getDrawable(R.drawable.cursor_optional)
        // Editor drafts can contain passwords and OTP seeds, including custom named fields.
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    protected fun input(hintValue: String, secret: Boolean = false) = EditText(context).apply {
        hint = hintValue
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
        configureInput(this)
        if (secret) {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
    protected fun smooth(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Float = 0f) =
        SmoothDrawable(color, context.resources.displayMetrics.density, radius, stroke, strokeWidth)
    protected fun underline() = UnderlineDrawable(context.resources.displayMetrics.density, 0xff969696.toInt(), FOREGROUND)
    protected fun autoScrollDuringDrag(list: ScrollView, rawY: Float) {
        val position = IntArray(2); list.getLocationOnScreen(position)
        val edge = dp(56)
        val distance = when {
            rawY < position[1] + edge -> -((position[1] + edge - rawY) / edge).coerceIn(0f, 1f)
            rawY > position[1] + list.height - edge -> ((rawY - position[1] - list.height + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        list.scrollBy(0, (distance * dp(5)).toInt())
    }
}
