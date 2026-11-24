package com.authnotes.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.authnotes.R
import com.authnotes.data.AccountCard
import com.authnotes.data.AccountRepository

internal class AccountListPage(
    context: Context,
    parent: ViewGroup,
    private val repository: AccountRepository,
    onDragChanged: (Boolean) -> Unit,
    notify: (String) -> Unit,
    private val onOpen: (AccountCard, View) -> Unit,
) : CardListPage(context, parent, onDragChanged, repository::reorder, notify) {
    override fun bindRows(query: String, animate: Boolean) {
        val entries = repository.list(query)
        val showPreview = repository.showPreviewValues()
        bindEntries(entries, showPreview, animate)
    }

    fun bindPrepared(query: String, entries: List<com.authnotes.data.AccountListEntry>, showPreview: Boolean, animate: Boolean) {
        val byId = entries.associateBy { it.card.id }
        reconcileRows(query, entries.map { it.card.id }, R.string.account_list_empty, animate) { id, existing ->
            val entry = checkNotNull(byId[id])
            bindEntry(entry, showPreview, existing)
        }
    }

    private fun bindEntry(entry: com.authnotes.data.AccountListEntry, showPreview: Boolean, existing: View?): View {
        val card = entry.card
        val row = if (existing != null && existing.getTag(R.id.drag_color) == card.color) existing
            else inflateCard(R.layout.item_account_card, card.id, card.color) { onOpen(card, it) }
        bindCard(row, card.id, card.color) { onOpen(card, it) }
        row.findViewById<TextView>(R.id.card_label).text = card.tag
        row.findViewById<View>(R.id.account_attachment_marker).visibility =
            if (entry.hasAttachments) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.account_preview).apply {
            val firstField = entry.firstFieldValue
            if (firstField.isNotBlank()) {
                text = if (showPreview) firstField else "•".repeat(firstField.length.coerceAtMost(24))
                visibility = View.VISIBLE
            } else visibility = View.GONE
        }
        row.findViewById<TextView>(R.id.account_remark).apply {
            text = card.remark
            visibility = if (card.remark.isNotBlank()) View.VISIBLE else View.GONE
        }
        return row
    }

    private fun bindEntries(entries: List<com.authnotes.data.AccountListEntry>, showPreview: Boolean, animate: Boolean) {
        if (entries.isEmpty()) showEmpty(R.string.account_list_empty)
        entries.forEachIndexed { index, entry -> addCard(bindEntry(entry, showPreview, null), index, animate) }
    }
}
