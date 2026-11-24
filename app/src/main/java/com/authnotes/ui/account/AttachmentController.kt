package com.authnotes.ui.account

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.authnotes.R
import com.authnotes.VaultShareProvider
import com.authnotes.data.AccountRepository
import com.authnotes.data.CardAttachment
import com.authnotes.data.NewCardAttachment
import com.authnotes.data.VaultDatabase
import com.authnotes.ui.EditorViews
import com.authnotes.ui.ZoomRotateImageView
import com.authnotes.ui.decodeAttachmentPreview
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executor

/** Attachment IO and presentation. The Activity owns system request codes and forwards results. */
internal class AttachmentController(
    private val activity: Activity,
    private val accounts: AccountRepository,
    private val io: Executor,
    private val main: Handler,
    private val notify: (String) -> Unit,
    private val launchPick: () -> Unit,
    private val launchSave: (String, String) -> Unit,
) : EditorViews(activity) {
    private data class PreparedAttachment(val item: CardAttachment, val thumbnail: Bitmap?)
    private var disposed = false
    private val isAlive get() = !disposed && !activity.isDestroyed && !activity.isFinishing
    private var previewDialog: AlertDialog? = null
    private var pendingAttachmentSave: NewCardAttachment? = null
    private var pendingPick: Pair<View, (List<Uri>) -> Unit>? = null

    fun pickFiles(owner: View, onPicked: (List<Uri>) -> Unit) {
        pendingPick = owner to onPicked
        launchPick()
    }

    fun onPickResult(resultCode: Int, data: Intent?) {
        val target = pendingPick
        pendingPick = null
        if (resultCode != Activity.RESULT_OK || target == null || data == null || !isAlive || !target.first.isAttachedToWindow) return
        val uris = buildList {
            data.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
            data.data?.let { add(it) }
        }.distinct()
        target.second(uris)
    }

    fun onSaveResult(resultCode: Int, uri: Uri?) {
        val attachment = pendingAttachmentSave
        pendingAttachmentSave = null
        if (resultCode != Activity.RESULT_OK || attachment == null || uri == null || !isAlive) return
        io.execute {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri, "w")?.use { it.write(attachment.content) }
                    ?: error("无法写入所选位置")
            }
            main.post {
                if (isAlive) result.onSuccess { notify("附件已保存") }.onFailure { notify(it.message ?: "附件保存失败") }
            }
        }
    }

    fun dispose() {
        disposed = true
        dismissPreview()
        pendingPick = null
        pendingAttachmentSave = null
    }

    fun dismissPreview() {
        previewDialog?.dismiss()
        previewDialog = null
    }

    fun load(cardId: Long, section: LinearLayout, isCurrent: () -> Boolean) {
        val previewWidth = dp(92)
        val previewHeight = dp(82)
        io.execute {
            val prepared = mutableListOf<PreparedAttachment>()
            val result = runCatching {
                accounts.attachments(cardId).forEach { attachment ->
                    prepared += if (attachment.mimeType !in SUPPORTED_IMAGE_TYPES) PreparedAttachment(attachment, null) else {
                        val bytes = accounts.attachmentContent(attachment.id)
                        PreparedAttachment(attachment, bytes?.let { decodeAttachmentPreview(it, previewWidth, previewHeight) })
                    }
                }
                prepared
            }
            main.post {
                if (!isAlive || !isCurrent() || result.isFailure) {
                    prepared.forEach { it.thumbnail?.recycle() }
                    if (!isAlive || !isCurrent()) return@post
                }
                section.removeAllViews()
                val items = result.getOrElse {
                    section.addView(text("附件加载失败", 13, color = MUTED))
                    return@post
                }
                if (items.isEmpty()) {
                    section.visibility = View.GONE
                    return@post
                }
                section.addView(text("附件", 14, true, MUTED).apply { setPadding(0, 0, 0, dp(4)) })
                val previews = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                items.forEach { item ->
                    if (item.thumbnail != null) {
                        previews.addView(ImageView(activity).apply {
                            setImageBitmap(item.thumbnail); scaleType = ImageView.ScaleType.FIT_CENTER
                            background = smooth(Color.WHITE, 10f, RULE, 1f); clipToOutline = true
                            contentDescription = item.item.name
                            setOnClickListener { showAttachmentPreview(item.item) }
                        }, LinearLayout.LayoutParams(dp(92), dp(82)).apply { rightMargin = dp(8) })
                    } else section.addView(attachmentFileRow(item.item, section))
                }
                if (previews.childCount > 0) section.addView(HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(previews)
                }, LinearLayout.LayoutParams(-1, dp(90)))
            }
        }
    }

    private fun attachmentFileRow(attachment: CardAttachment, parent: LinearLayout) =
        activity.layoutInflater.inflate(R.layout.item_attachment_file, parent, false).apply {
            findViewById<TextView>(R.id.attachment_file_name).apply { text = attachment.name; isSelected = true }
            findViewById<TextView>(R.id.attachment_file_size).text = formatSize(attachment.size)
            findViewById<View>(R.id.attachment_file_share).setOnClickListener { shareAttachment(attachment) }
            findViewById<View>(R.id.attachment_file_save).setOnClickListener { saveAttachment(attachment) }
        }

    fun read(uri: Uri): NewCardAttachment {
        val mimeType = activity.contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: "application/octet-stream"
        val name = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "附件"
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        activity.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= VaultDatabase.MAX_ATTACHMENT_BYTES_PER_FILE) { "单个附件不能超过 8 MiB" }
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalArgumentException("无法打开附件文件")
        val bytes = output.toByteArray()
        require(bytes.isNotEmpty()) { "附件文件为空" }
        if (mimeType in SUPPORTED_IMAGE_TYPES) {
            val preview = decodeAttachmentPreview(bytes, dp(16), dp(16))
            require(preview != null) { "图片文件无法预览" }
            preview.recycle()
        }
        return NewCardAttachment(name, mimeType, bytes)
    }

    private fun showAttachmentPreview(attachment: CardAttachment) {
        val previewWidth = (activity.window.decorView.width - dp(40)).coerceAtLeast(dp(180)).coerceAtMost(dp(440))
        val previewHeight = (activity.window.decorView.height - dp(240)).coerceAtLeast(dp(220)).coerceAtMost(dp(680))
        val frame = activity.layoutInflater.inflate(R.layout.view_attachment_preview, null, false) as FrameLayout
        val image = frame.findViewById<ZoomRotateImageView>(R.id.attachment_preview_image).apply {
            contentDescription = "${attachment.name}，双指缩放并旋转，单指移动"
            background = smooth(Color.WHITE, 18f)
            layoutParams = FrameLayout.LayoutParams(previewWidth, previewHeight, Gravity.CENTER)
        }
        val loading = frame.findViewById<ProgressBar>(R.id.attachment_preview_loading)
        previewDialog?.dismiss()
        val dialog = AlertDialog.Builder(activity).setView(frame)
            .setNegativeButton("分享", null)
            .setNeutralButton("保存", null)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(smooth(Color.WHITE, 24f))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(BLUE); setOnClickListener { shareAttachment(attachment) }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(BLUE); setOnClickListener { saveAttachment(attachment) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(MUTED)
        }
        previewDialog = dialog
        dialog.setOnDismissListener { image.setImageBitmap(null); if (previewDialog === dialog) previewDialog = null }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.setLayout(previewWidth + dp(32), WindowManager.LayoutParams.WRAP_CONTENT)
        io.execute {
            val loaded = runCatching {
                val bytes = accounts.attachmentContent(attachment.id) ?: error("附件不存在")
                decodeAttachmentPreview(bytes, previewWidth, previewHeight) ?: error("图片预览失败")
            }
            main.post {
                if (!dialog.isShowing || !isAlive) {
                    loaded.getOrNull()?.recycle()
                    return@post
                }
                loading.visibility = View.GONE
                loaded.onSuccess(image::setImageBitmap).onFailure {
                    frame.removeView(image)
                    frame.addView(text("图片预览失败", 14, color = MUTED).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
    }

    private fun shareAttachment(attachment: CardAttachment) = withAttachmentContent(attachment) { bytes ->
        val token = VaultShareProvider.registerAttachment(attachment.name, attachment.mimeType, bytes)
        val uri = Uri.Builder().scheme("content").authority("${activity.packageName}.backups")
            .appendPath("attachments").appendPath(token).build()
        val send = Intent(Intent.ACTION_SEND).setType(attachment.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(attachment.name, uri)
        activity.startActivity(Intent.createChooser(send, "分享附件"))
    }

    private fun saveAttachment(attachment: CardAttachment) = withAttachmentContent(attachment) { bytes ->
        pendingAttachmentSave = NewCardAttachment(attachment.name, attachment.mimeType, bytes)
        launchSave(attachment.name, attachment.mimeType)
    }

    private fun withAttachmentContent(attachment: CardAttachment, use: (ByteArray) -> Unit) {
        notify("正在读取附件…")
        io.execute {
            val result = runCatching { accounts.attachmentContent(attachment.id) ?: error("附件不存在") }
            main.post {
                if (!isAlive) return@post
                result.onSuccess(use).onFailure { notify(it.message ?: "附件读取失败") }
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private companion object {
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")
    }
}
