package com.authnotes.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.authnotes.R
import com.authnotes.data.AccountCard
import com.authnotes.data.VaultDatabase

internal class AccountListPage(
    context: Context,
    parent: ViewGroup,
    private val database: () -> VaultDatabase,
    onDragChanged: (Boolean) -> Unit,
    notify: (String) -> Unit,
    private val onOpen: (AccountCard, View) -> Unit,
) : CardListPage(context, parent, onDragChanged, { database().reorderCards(it) }, notify) {
    override fun bindRows(query: String, animate: Boolean) {
        val db = database()
        val cards = db.cards(query)
        val showPreview = db.setting("show_card_preview", "0") == "1"
        if (cards.isEmpty()) showEmpty(R.string.account_list_empty)
        cards.forEachIndexed { index, card ->
            val row = inflateCard(R.layout.item_account_card, card.id, card.color) { onOpen(card, it) }
            row.findViewById<TextView>(R.id.card_label).text = card.tag
            if (db.attachments(card.id).isNotEmpty()) {
                row.findViewById<View>(R.id.account_attachment_marker).visibility = View.VISIBLE
            }
            val firstField = db.fields(card.id).firstOrNull()?.value.orEmpty()
            if (firstField.isNotBlank()) row.findViewById<TextView>(R.id.account_preview).apply {
                text = if (showPreview) firstField else "•".repeat(firstField.length.coerceAtMost(24))
                visibility = View.VISIBLE
            }
            if (card.remark.isNotBlank()) row.findViewById<TextView>(R.id.account_remark).apply {
                text = card.remark
                visibility = View.VISIBLE
            }
            addCard(row, index, animate)
        }
    }
}
