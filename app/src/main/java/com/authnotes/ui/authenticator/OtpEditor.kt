package com.authnotes.ui.authenticator

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import com.authnotes.R
import com.authnotes.data.AuthenticatorRepository
import com.authnotes.data.TotpAccount
import com.authnotes.data.otpConfig
import com.authnotes.security.Totp
import com.authnotes.security.TotpConfig
import com.authnotes.ui.ColorStripView
import com.authnotes.ui.EditorViews

/** One form for the inline add panel, scanned accounts and floating account editing. */
internal class OtpEditor(
    context: Context,
    private val repository: AuthenticatorRepository,
    private val entry: TotpAccount?,
    scanned: TotpConfig?,
    initialColor: Int,
    palette: IntArray,
    onEditingTouch: (Boolean) -> Unit,
    private val notify: (String) -> Unit,
) : EditorViews(context) {
    private val defaults = scanned ?: entry?.otpConfig()
    val body = LayoutInflater.from(context).inflate(R.layout.view_otp_editor, null, false) as LinearLayout
    private var color = entry?.color ?: initialColor
    private val issuer = body.findViewById<AutoCompleteTextView>(R.id.otp_editor_issuer).apply {
        configureInput(this)
        textCursorDrawable = context.getDrawable(R.drawable.cursor_required)
        setText(defaults?.issuer.orEmpty()); threshold = 1
        val issuers = repository.all().map { it.issuer }.filter { it.isNotBlank() }.distinct().toTypedArray()
        setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, issuers))
        setOnClickListener { if (issuers.isNotEmpty()) showDropDown() }
    }
    private val account = field(R.id.otp_editor_account, defaults?.account.orEmpty())
    private val secret = field(R.id.otp_editor_secret, defaults?.secret.orEmpty())
    private val type = spinner(R.id.otp_editor_type, listOf("TOTP", "HOTP"), if (defaults?.type == "HOTP") 1 else 0)
    private val algorithms = listOf("SHA1", "SHA256", "SHA512")
    private val algorithm = spinner(R.id.otp_editor_algorithm, algorithms, algorithms.indexOf(defaults?.algorithm ?: "SHA1").coerceAtLeast(0))
    private val digits = spinner(R.id.otp_editor_digits, listOf("6 位", "8 位"), if (defaults?.digits == 8) 1 else 0)
    private val period = field(R.id.otp_editor_period, (defaults?.period ?: 30).toString())
    private val counter = field(R.id.otp_editor_counter, (defaults?.counter ?: 0L).toString())
    private val periodRow = body.findViewById<View>(R.id.otp_editor_period_row)
    private val counterRow = body.findViewById<View>(R.id.otp_editor_counter_row)

    init {
        body.findViewById<FrameLayout>(R.id.otp_editor_color_slot).addView(
            ColorStripView(context, palette, color) { color = it }.apply { onGesture = onEditingTouch },
            FrameLayout.LayoutParams(-1, -1)
        )
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateTypeFields()
        }
        updateTypeFields()
    }

    private fun field(id: Int, value: String) = body.findViewById<EditText>(id).apply {
        configureInput(this); setText(value)
    }

    private fun spinner(id: Int, options: List<String>, selection: Int) = body.findViewById<Spinner>(id).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(selection); background = underline(); isSaveEnabled = false
    }

    private fun updateTypeFields() {
        val isHotp = type.selectedItemPosition == 1
        periodRow.visibility = if (isHotp) View.GONE else View.VISIBLE
        counterRow.visibility = if (isHotp) View.VISIBLE else View.GONE
    }

    fun save(done: () -> Unit) {
        runCatching {
            require(issuer.text.isNotBlank() || account.text.isNotBlank()) { "请填写服务商或账户" }
            val selectedType = type.selectedItem.toString()
            val selectedCounter = if (selectedType == "HOTP") counter.text.toString().toLongOrNull()
                ?: throw IllegalArgumentException("HOTP 计数器无效") else 0L
            val config = TotpConfig(issuer.text.toString(), account.text.toString(), Totp.normalizeSecret(secret.text.toString()),
                algorithm.selectedItem.toString(), if (digits.selectedItemPosition == 1) 8 else 6,
                period.text.toString().toIntOrNull() ?: 0, selectedType, selectedCounter)
            Totp.code(config)
            repository.save(config.issuer, config.account, config.secret, config.algorithm, config.digits, config.period,
                color, config.type, config.counter, entry?.id)
        }.onSuccess { done() }.onFailure { notify(it.message ?: "密钥或参数无效") }
    }
}
