package com.authnotes.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.authnotes.R
import com.authnotes.data.TotpAccount
import com.authnotes.data.AuthenticatorRepository
import com.authnotes.data.otpConfig
import com.authnotes.security.Totp

internal class AuthenticatorListPage(
    context: Context,
    parent: ViewGroup,
    private val repository: AuthenticatorRepository,
    onDragChanged: (Boolean) -> Unit,
    notify: (String) -> Unit,
    private val onOpen: (TotpAccount, View) -> Unit,
) : CardListPage(context, parent, onDragChanged, repository::reorder, notify) {
    private data class CodeRow(var account: TotpAccount, val code: TextView, val countdown: View)
    private val codes = mutableListOf<CodeRow>()

    override fun bindRows(query: String, animate: Boolean) {
        val accounts = repository.list(query)
        codes.clear()
        accounts.forEachIndexed { index, account -> addCard(bindAccount(account, null), index, animate) }
    }

    fun bindPrepared(query: String, accounts: List<TotpAccount>, animate: Boolean) {
        val byId = accounts.associateBy { it.id }
        codes.clear()
        reconcileRows(query, accounts.map { it.id }, R.string.otp_list_empty, animate) { id, existing ->
            bindAccount(checkNotNull(byId[id]), existing)
        }
    }

    private fun bindAccount(account: TotpAccount, existing: View?): View {
        val row = if (existing != null && existing.getTag(R.id.drag_color) == account.color) existing
            else inflateCard(R.layout.item_authenticator_card, account.id, account.color) { source ->
                codes.firstOrNull { it.account.id == account.id }?.let { onOpen(it.account, source) }
            }
        bindCard(row, account.id, account.color) { source ->
            codes.firstOrNull { it.account.id == account.id }?.let { onOpen(it.account, source) }
        }
        row.findViewById<TextView>(R.id.card_label).text = account.account.ifBlank { account.issuer }
        row.findViewById<TextView>(R.id.otp_issuer).text = account.issuer.ifBlank { account.account }
        val code = CodeRow(account, row.findViewById(R.id.otp_code), row.findViewById(R.id.otp_countdown))
        code.code.tag = null
        codes += code
        val countdownTrack = row.findViewById<View>(R.id.otp_countdown_track)
        val nextCode = row.findViewById<View>(R.id.otp_next_code)
        if (account.type == "HOTP") {
            countdownTrack.visibility = View.GONE
            nextCode.visibility = View.VISIBLE
            nextCode.setOnClickListener { advanceHotp(code) }
            code.code.text = formatCode(account)
        } else {
            countdownTrack.visibility = View.VISIBLE
            nextCode.visibility = View.GONE
            code.countdown.background = surface(account.color, 4f)
            code.countdown.pivotX = 0f
        }
        return row
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
            row.account = row.account.copy(counter = repository.advanceHotp(row.account.id))
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
