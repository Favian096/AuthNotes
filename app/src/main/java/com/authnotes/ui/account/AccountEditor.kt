package com.authnotes.ui.account

import android.animation.ValueAnimator
import android.app.Activity
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.authnotes.R
import com.authnotes.data.AccountCard
import com.authnotes.data.AccountRepository
import com.authnotes.data.NewCardAttachment
import com.authnotes.data.VaultDatabase
import com.authnotes.ui.EditorViews
import com.authnotes.ui.ColorStripView
import com.authnotes.ui.ReorderColumn
import kotlin.math.abs

internal class AccountEditor(
    private val activity: Activity,
    private val repository: AccountRepository,
    private val card: AccountCard?,
    initialColor: Int,
    palette: IntArray,
    private val notify: (String) -> Unit,
    private val readAttachment: (Uri) -> NewCardAttachment,
    private val pickAttachments: (AccountEditor) -> Unit,
    private val onDragging: (Boolean) -> Unit,
    private val onEditingTouch: (Boolean) -> Unit,
) : EditorViews(activity) {
    val body = activity.layoutInflater.inflate(R.layout.view_account_editor, null, false) as LinearLayout
    private val tag = body.findViewById<EditText>(R.id.account_editor_tag).apply {
        configureInput(this)
        setText(card?.tag.orEmpty())
        textCursorDrawable = activity.getDrawable(R.drawable.cursor_required)
    }
    private val remark = body.findViewById<EditText>(R.id.account_editor_remark).apply {
        configureInput(this); setText(card?.remark.orEmpty())
    }
    private var color = card?.color ?: initialColor
    private val fields = body.findViewById<ReorderColumn>(R.id.account_editor_fields).apply {
        onDragStateChanged = onDragging
        onEdgeDrag = { rawY -> (body.parent as? ScrollView)?.let { autoScrollDuringDrag(it, rawY) } }
    }
    private val retainedAttachments = card?.let { repository.attachments(it.id).toMutableList() } ?: mutableListOf()
    private val newAttachments = mutableListOf<NewCardAttachment>()
    private val attachmentRows = body.findViewById<LinearLayout>(R.id.account_editor_attachment_rows)

    init {
        body.findViewById<FrameLayout>(R.id.account_editor_color_slot).addView(
            ColorStripView(activity, palette, color) { color = it }.apply {
                onGesture = onEditingTouch
            }, FrameLayout.LayoutParams(-1, -1)
        )
        val initialFields = if (card == null) listOf("账号" to "", "密码" to "")
            else repository.fields(card.id).map { it.name to it.value }
        initialFields.forEach { addField(it.first, it.second) }
        body.findViewById<TextView>(R.id.account_editor_add_field).apply {
            visibility = if (card == null) View.GONE else View.VISIBLE
            setOnClickListener { addCustomField() }
        }
        body.findViewById<TextView>(R.id.account_editor_add_attachment).setOnClickListener { pickAttachments(this) }
        refreshAttachmentRows()
    }

    fun save(done: () -> Unit) {
        val values = (0 until fields.childCount).map { index ->
            @Suppress("UNCHECKED_CAST")
            val pair = fields.getChildAt(index).tag as Pair<EditText, EditText>
            pair.first.text.toString() to pair.second.text.toString()
        }
        runCatching {
            repository.save(card?.id, tag.text.toString(), color, remark.text.toString(), values,
                retainedAttachments.map { it.id }.toSet(), newAttachments.toList())
        }.onSuccess { done() }.onFailure { notify(it.message ?: "保存失败") }
    }

    fun addAttachments(uris: List<Uri>) {
        var added = 0
        for (uri in uris) {
            if (retainedAttachments.size + newAttachments.size >= VaultDatabase.MAX_ATTACHMENTS_PER_CARD) {
                notify("每张卡片最多添加 ${VaultDatabase.MAX_ATTACHMENTS_PER_CARD} 个附件")
                break
            }
            runCatching { readAttachment(uri) }
                .onSuccess { attachment ->
                    val total = retainedAttachments.sumOf { it.size } + newAttachments.sumOf { it.content.size.toLong() }
                    if (total + attachment.content.size.toLong() > VaultDatabase.MAX_ATTACHMENT_BYTES_PER_CARD) {
                        notify("单张卡片附件总大小不能超过 24 MiB")
                    } else {
                        newAttachments += attachment
                        added++
                    }
                }
                .onFailure { notify(it.message ?: "无法读取附件") }
        }
        if (added > 0) refreshAttachmentRows()
    }

    fun addCustomField() = addField("", "")

    private fun addField(nameText: String, valueText: String) {
        val row = activity.layoutInflater.inflate(R.layout.item_account_editor_field, fields, false) as LinearLayout
        val name = row.findViewById<EditText>(R.id.field_name).apply { configureInput(this); setText(nameText) }
        val value = row.findViewById<EditText>(R.id.field_value).apply { configureInput(this); setText(valueText) }
        row.tag = name to value
        row.findViewById<TextView>(R.id.field_drag_handle).apply {
            var y = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { y = event.rawY; onEditingTouch(true); parent.requestDisallowInterceptTouchEvent(true) }
                    MotionEvent.ACTION_MOVE -> if (abs(event.rawY - y) > dp(6)) {
                        onEditingTouch(false)
                        fields.begin(row, event.rawY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        onEditingTouch(false)
                        if (!fields.isReordering) parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                true
            }
        }
        row.findViewById<TextView>(R.id.field_remove).setOnClickListener {
            it.isEnabled = false
            ValueAnimator.ofInt(row.height, 0).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    row.layoutParams = row.layoutParams.apply { this.height = height }
                    row.alpha = (height.toFloat() / dp(48)).coerceIn(0f, 1f)
                    if (height == 0 && row.parent === fields) fields.removeView(row)
                }
                start()
            }
        }
        fields.addView(row, LinearLayout.LayoutParams(-1, if (fields.isLaidOut) 0 else dp(48)))
        if (fields.isLaidOut) {
            ValueAnimator.ofInt(0, dp(48)).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val height = animator.animatedValue as Int
                    row.layoutParams = row.layoutParams.apply { this.height = height }
                    row.alpha = (height.toFloat() / dp(48)).coerceIn(0f, 1f)
                }
                start()
            }
        }
    }

    private fun refreshAttachmentRows() {
        attachmentRows.removeAllViews()
        retainedAttachments.forEach { attachment ->
            attachmentRows.addView(attachmentRow(attachment.name, attachment.size) {
                retainedAttachments.remove(attachment)
                refreshAttachmentRows()
            })
        }
        newAttachments.forEach { attachment ->
            attachmentRows.addView(attachmentRow(attachment.name, attachment.content.size.toLong()) {
                newAttachments.remove(attachment)
                refreshAttachmentRows()
            })
        }
    }

    private fun attachmentRow(name: String, size: Long, remove: () -> Unit) =
        activity.layoutInflater.inflate(R.layout.item_attachment_editor, attachmentRows, false).apply {
            findViewById<TextView>(R.id.attachment_editor_name).text = "$name  ·  ${size / 1024} KiB"
            findViewById<View>(R.id.attachment_editor_remove).setOnClickListener { remove() }
        }
}
