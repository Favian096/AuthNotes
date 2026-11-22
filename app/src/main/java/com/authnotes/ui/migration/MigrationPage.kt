package com.authnotes.ui.migration

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.authnotes.R
import com.authnotes.data.TotpAccount
import com.authnotes.data.otpConfig
import com.authnotes.security.GoogleMigration
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal class MigrationPage(
    parent: LinearLayout,
    accounts: List<TotpAccount>,
    onScan: () -> Unit,
    private val notify: (String) -> Unit,
) {
    private val context = parent.context
    private val view = LayoutInflater.from(context).inflate(R.layout.view_migration, parent, false)
    private val qrPanel = view.findViewById<View>(R.id.migration_qr_panel)
    private val qrImage = view.findViewById<ImageView>(R.id.migration_qr_image)
    private val qrPosition = view.findViewById<TextView>(R.id.migration_qr_position)
    private val qrControls = view.findViewById<View>(R.id.migration_qr_controls)
    private var uris = emptyList<String>()
    private var qrIndex = 0

    init {
        view.isSaveFromParentEnabled = false
        val selected = accounts.mapTo(linkedSetOf()) { it.id }
        val rows = view.findViewById<LinearLayout>(R.id.migration_accounts)
        if (accounts.isEmpty()) rows.addView(TextView(context).apply {
            setText(R.string.migration_empty)
            setTextColor(context.getColor(R.color.text_muted))
            gravity = Gravity.CENTER
            val padding = dp(72)
            setPadding(0, padding, 0, padding)
        })
        accounts.forEach { entry ->
            val check = CheckBox(context).apply {
                val label = listOf(entry.issuer, entry.account).filter { it.isNotBlank() }.joinToString(" · ")
                text = "$label  (${entry.type})"
                isChecked = true
                buttonTintList = ColorStateList.valueOf(context.getColor(R.color.accent_blue))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += entry.id else selected -= entry.id
                }
            }
            rows.addView(check, LinearLayout.LayoutParams(-1, dp(48)))
        }
        view.findViewById<View>(R.id.migration_scan).setOnClickListener { onScan() }
        view.findViewById<View>(R.id.migration_generate).setOnClickListener {
            runCatching {
                GoogleMigration.encodeUris(accounts.filter { it.id in selected }.map { it.otpConfig() })
            }.onSuccess { generated ->
                uris = generated
                showQr(0)
            }.onFailure { notify(it.message ?: context.getString(R.string.migration_generate_failed)) }
        }
        view.findViewById<View>(R.id.migration_qr_previous).setOnClickListener {
            showQr((qrIndex - 1).coerceAtLeast(0))
        }
        view.findViewById<View>(R.id.migration_qr_next).setOnClickListener {
            showQr((qrIndex + 1).coerceAtMost(uris.lastIndex))
        }
        parent.addView(view)
    }

    fun clearExport() {
        uris = emptyList()
        qrIndex = 0
        qrImage.setImageDrawable(null)
        qrPanel.visibility = View.GONE
    }

    private fun showQr(index: Int) {
        val uri = uris.getOrNull(index) ?: return
        val matrix = runCatching {
            val hints = mapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 640, 640, hints)
        }.getOrElse {
            notify(context.getString(R.string.migration_generate_failed))
            return
        }
        val pixels = IntArray(matrix.width * matrix.height) {
            if (matrix[it % matrix.width, it / matrix.width]) Color.BLACK else Color.WHITE
        }
        qrIndex = index
        qrImage.setImageBitmap(Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888))
        qrImage.contentDescription = context.getString(R.string.migration_qr_description, index + 1, uris.size)
        qrPosition.text = context.getString(R.string.migration_qr_position, index + 1, uris.size)
        qrControls.visibility = if (uris.size > 1) View.VISIBLE else View.GONE
        qrPanel.visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
}
