package com.authnotes.data

/** Account list projection and ordering boundary for home UI. */
internal class AccountRepository(private val database: () -> VaultDatabase) {
    fun list(query: String) = database().accountList(query)
    fun showPreviewValues() = database().setting("show_card_preview", "0") == "1"
    fun reorder(ids: List<Long>) = database().reorderCards(ids)
    fun deletedCards() = database().cards(deleted = true)
    fun delete(id: Long, permanent: Boolean = false) = database().deleteCard(id, permanent)
    fun restore(id: Long) = database().restoreCard(id)
    fun import(entries: List<Triple<String, String, String>>, color: Int) = database().importCards(entries, color)
    fun fields(cardId: Long) = database().fields(cardId)
    fun attachments(cardId: Long) = database().attachments(cardId)
    fun attachmentContent(id: Long) = database().attachmentContent(id)
    fun save(cardId: Long?, tag: String, color: Int, remark: String, fields: List<Pair<String, String>>,
        keepAttachmentIds: Set<Long>, newAttachments: List<NewCardAttachment>) =
        database().saveCard(cardId, tag, color, remark, fields, keepAttachmentIds, newAttachments)
}
