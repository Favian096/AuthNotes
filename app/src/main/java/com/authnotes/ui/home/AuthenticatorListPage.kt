package com.authnotes.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.authnotes.R
import com.authnotes.data.TotpAccount
import com.authnotes.data.VaultDatabase
import com.authnotes.data.otpConfig
import com.authnotes.security.Totp

internal class AuthenticatorListPage(
    context: Context,
    parent: ViewGroup,
    private val database: () -> VaultDatabase,
    onDragChanged: (Boolean) -> Unit,
    notify: (String) -> Unit,
    private val onOpen: (TotpAccount, View) -> Unit,
) : CardListPage(context, parent, onDragChanged, { database().reorderTotp(it) }, notify) {
    private data class CodeRow(var account: TotpAccount, val code: TextView, val countdown: View)
    private val codes = mutableListOf<CodeRow>()

    override fun bindRows(query: String, animate: Boolean) {
        codes.clear()
        val accounts = database().totpAccounts(query)
        if (accounts.isEmpty()) showEmpty(R.string.otp_list_empty)
        accounts.forEachIndexed { index, account ->
            val row = inflateCard(R.layout.item_authenticator_card, account.id, account.color) { source ->
                codes.firstOrNull { it.account.id == account.id }?.let { onOpen(it.account, source) }
            }
            row.findViewById<TextView>(R.id.card_label).text = account.account.ifBlank { account.issuer }
            row.findViewById<TextView>(R.id.otp_issuer).text = account.issuer.ifBlank { account.account }
            val code = CodeRow(account, row.findViewById(R.id.otp_code), row.findViewById(R.id.otp_countdown))
            codes += code
            if (account.type == "HOTP") {
                row.findViewById<View>(R.id.otp_countdown_track).visibility = View.GONE
                row.findViewById<View>(R.id.otp_next_code).apply {
                    visibility = View.VISIBLE
                    setOnClickListener { advanceHotp(code) }
                }
                code.code.text = formatCode(account)
            } else {
                code.countdown.background = surface(account.color, 4f)
                code.countdown.pivotX = 0f
            }
            addCard(row, index, animate)
        }
    }

    fun updateCodes(offsetMillis: Long) {
        val now = System.currentTimeMillis() + offsetMillis
        codes.forEach { (account, code, countdown) ->
            if (account.type == "HOTP") return@forEach
            val periodMillis = account.period * 1000L
            val step = Math.floorDiv(now, periodMillis)
            if (code.tag != step) {
                val hadCode = code.tag != null
                code.tag = step
                val value = formatCode(account, now)
                if (hadCode && view.visibility == View.VISIBLE) animateCode(code, value)
                else code.text = value
            }
            val elapsed = Math.floorMod(now, periodMillis).toFloat() / periodMillis
            countdown.scaleX = (1f - elapsed).coerceIn(0f, 1f)
        }
    }

    private fun advanceHotp(row: CodeRow) {
        runCatching {
            row.account = row.account.copy(counter = database().advanceHotpCounter(row.account.id))
            formatCode(row.account)
        }.onSuccess { animateCode(row.code, it) }
            .onFailure { notify(it.message ?: context.getString(R.string.otp_next_code_failed)) }
    }

    private fun formatCode(account: TotpAccount, now: Long = System.currentTimeMillis()): String = runCatching {
        Totp.code(account.otpConfig(), now).chunked(if (account.digits == 8) 4 else 3).joinToString(" ")
    }.getOrElse { context.getString(R.string.otp_invalid_secret) }

    private fun animateCode(code: TextView, value: String) {
        code.animate().cancel()
        code.animate().alpha(0f).translationY(-dp(4).toFloat()).setDuration(80).withEndAction {
            code.text = value
            code.translationY = dp(4).toFloat()
            code.animate().alpha(1f).translationY(0f).setDuration(150).start()
        }.start()
    }
}
