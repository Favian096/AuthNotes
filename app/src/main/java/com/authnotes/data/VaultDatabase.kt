package com.authnotes.data

import android.content.ContentValues
import android.database.Cursor
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import com.authnotes.security.TotpConfig
import java.io.File

// Timestamps are epoch milliseconds. Lower sortOrder values appear first.
data class AccountCard(val id: Long, val tag: String, val createTime: Long, val updateTime: Long,
    val color: Int, val remark: String, val order: Long)
data class CardField(val id: Long, val name: String, val value: String, val order: Long)
data class CardAttachment(val id: Long, val cardId: Long, val name: String, val mimeType: String, val size: Long, val order: Long)
data class NewCardAttachment(val name: String, val mimeType: String, val content: ByteArray)
data class TotpAccount(val id: Long, val issuer: String, val account: String, val secret: String,
    val algorithm: String, val digits: Int, val period: Int, val type: String, val counter: Long, val color: Int, val order: Long)

internal fun TotpAccount.otpConfig() = TotpConfig(issuer, account, secret, algorithm, digits, period, type, counter)

class VaultDatabase private constructor(private val db: SQLiteDatabase) : AutoCloseable {
    @Synchronized fun cards(query: String = "", deleted: Boolean = false): List<AccountCard> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.rawQuery(
            "SELECT id,tag,create_time,update_time,color,remark,sort_order FROM account_cards " +
                "WHERE deleted=? AND (tag LIKE ? ESCAPE '\\' OR remark LIKE ? ESCAPE '\\') ORDER BY sort_order,id DESC",
            arrayOf(if (deleted) "1" else "0", "%$escaped%", "%$escaped%")
        ).use { it.collect { c -> AccountCard(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4), c.getString(5), c.getLong(6)) } }
    }

    @Synchronized fun fields(cardId: Long): List<CardField> = db.rawQuery(
        "SELECT id,name,value,sort_order FROM card_fields WHERE card_id=? ORDER BY sort_order,id", arrayOf(cardId.toString())
    ).use { it.collect { c -> CardField(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)) } }

    @Synchronized fun attachments(cardId: Long): List<CardAttachment> = db.rawQuery(
        "SELECT id,card_id,name,mime_type,length(content),sort_order FROM card_attachments WHERE card_id=? ORDER BY sort_order,id",
        arrayOf(cardId.toString())
    ).use { it.collect { c -> CardAttachment(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5)) } }

    @Synchronized fun attachmentContent(id: Long): ByteArray? = db.rawQuery(
        "SELECT content FROM card_attachments WHERE id=?", arrayOf(id.toString())
    ).use { if (it.moveToFirst()) it.getBlob(0) else null }

    @Synchronized fun saveCard(id: Long?, tag: String, color: Int, remark: String, fields: List<Pair<String, String>>,
        keepAttachmentIds: Set<Long>? = null, newAttachments: List<NewCardAttachment> = emptyList()) {
        require(tag.isNotBlank()) { "标签不能为空" }
        require(fields.all { it.second.isEmpty() || it.first.isNotBlank() }) { "已填写内容的字段需要名称" }
        transaction {
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("tag", tag.trim()); put("update_time", now); put("color", color); put("remark", remark)
            }
            val cardId = if (id == null) {
                values.put("create_time", now)
                values.put("sort_order", nextOrder("account_cards"))
                db.insertOrThrow("account_cards", null, values)
            } else {
                check(db.update("account_cards", values, "id=?", arrayOf(id.toString())) == 1)
                db.delete("card_fields", "card_id=?", arrayOf(id.toString()))
                id
            }
            fields.filter { it.second.isNotEmpty() }.forEachIndexed { order, (name, value) ->
                db.insertOrThrow("card_fields", null, ContentValues().apply {
                    put("card_id", cardId); put("name", name.trim()); put("value", value); put("sort_order", order)
                })
            }
            if (keepAttachmentIds != null) {
                val oldIds = attachments(cardId).map { it.id }
                oldIds.filterNot { it in keepAttachmentIds }.forEach { attachmentId ->
                    db.delete("card_attachments", "id=? AND card_id=?", arrayOf(attachmentId.toString(), cardId.toString()))
                }
            }
            val existingAttachments = attachments(cardId)
            require(existingAttachments.size + newAttachments.size <= MAX_ATTACHMENTS_PER_CARD) { "每张卡片最多添加 $MAX_ATTACHMENTS_PER_CARD 个附件" }
            val existingSize = existingAttachments.sumOf { it.size }
            require(existingSize + newAttachments.sumOf { it.content.size.toLong() } <= MAX_ATTACHMENT_BYTES_PER_CARD) { "单张卡片附件总大小不能超过 24 MiB" }
            val nextAttachmentOrder = (existingAttachments.maxOfOrNull { it.order } ?: -1L) + 1L
            newAttachments.forEachIndexed { index, attachment ->
                require(attachment.mimeType.isNotBlank() && attachment.mimeType.length <= 255) { "文件类型无效" }
                require(attachment.content.isNotEmpty() && attachment.content.size <= MAX_ATTACHMENT_BYTES_PER_FILE) { "单个附件不能超过 8 MiB" }
                db.insertOrThrow("card_attachments", null, ContentValues().apply {
                    put("card_id", cardId); put("name", attachment.name); put("mime_type", attachment.mimeType)
                    put("content", attachment.content); put("sort_order", nextAttachmentOrder + index)
                })
            }
        }
    }

    @Synchronized fun importCards(entries: List<Triple<String, String, String>>, color: Int) = transaction {
        entries.forEach { (tag, account, password) ->
            saveCard(null, tag, color, "", listOf("账号" to account, "密码" to password))
        }
    }

    @Synchronized fun deleteCard(id: Long, permanent: Boolean = false) {
        if (permanent) db.delete("account_cards", "id=? AND deleted=1", arrayOf(id.toString()))
        else db.update("account_cards", ContentValues().apply { put("deleted", 1) }, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun restoreCard(id: Long) {
        db.update("account_cards", ContentValues().apply { put("deleted", 0) }, "id=?", arrayOf(id.toString()))
    }
    @Synchronized fun reorderCards(ids: List<Long>) = transaction {
        ids.forEachIndexed { order, id ->
            db.update("account_cards", ContentValues().apply { put("sort_order", order) }, "id=? AND deleted=0", arrayOf(id.toString()))
        }
    }

    @Synchronized fun totpAccounts(query: String = ""): List<TotpAccount> = db.rawQuery(
        "SELECT id,issuer,account,secret,algorithm,digits,period,type,counter,color,sort_order FROM totp_accounts ORDER BY sort_order,id", emptyArray<String>()
    ).use { it.collect { c -> TotpAccount(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getInt(6), c.getString(7), c.getLong(8), c.getInt(9), c.getLong(10)) } }
        .filter { it.issuer.contains(query, true) || it.account.contains(query, true) }

    @Synchronized fun saveTotp(issuer: String, account: String, secret: String, algorithm: String, digits: Int, period: Int,
        color: Int, type: String = "TOTP", counter: Long = 0L, id: Long? = null) {
        require(type == "TOTP" || type == "HOTP") { "认证类型无效" }
        require(counter >= 0L) { "HOTP 计数器无效" }
        val values = ContentValues().apply {
            put("issuer", issuer.trim()); put("account", account.trim()); put("secret", secret)
            put("algorithm", algorithm); put("digits", digits); put("period", period); put("type", type)
            put("counter", counter); put("color", color)
        }
        if (id == null) {
            values.put("sort_order", nextOrder("totp_accounts"))
            db.insertOrThrow("totp_accounts", null, values)
        } else check(db.update("totp_accounts", values, "id=?", arrayOf(id.toString())) == 1)
    }

    @Synchronized fun importTotp(configs: List<TotpConfig>, colors: List<Int>) = transaction {
        require(configs.size == colors.size)
        configs.forEachIndexed { index, config ->
            saveTotp(config.issuer, config.account, config.secret, config.algorithm, config.digits, config.period,
                colors[index], config.type, config.counter)
        }
    }

    @Synchronized fun advanceHotpCounter(id: Long): Long = transaction {
        val row = db.rawQuery("SELECT type,counter FROM totp_accounts WHERE id=?", arrayOf(id.toString())).use { cursor ->
            check(cursor.moveToFirst()) { "认证账户不存在" }
            cursor.getString(0) to cursor.getLong(1)
        }
        check(row.first == "HOTP") { "此账户不是 HOTP" }
        check(row.second < Long.MAX_VALUE) { "HOTP 计数器已达到上限" }
        val next = row.second + 1
        check(db.update("totp_accounts", ContentValues().apply { put("counter", next) }, "id=?", arrayOf(id.toString())) == 1)
        next
    }
    @Synchronized fun deleteTotp(id: Long) { db.delete("totp_accounts", "id=?", arrayOf(id.toString())) }
    @Synchronized fun reorderTotp(ids: List<Long>) = transaction {
        ids.forEachIndexed { order, id ->
            db.update("totp_accounts", ContentValues().apply { put("sort_order", order) }, "id=?", arrayOf(id.toString()))
        }
    }
    @Synchronized fun setting(key: String, default: String = ""): String = db.rawQuery("SELECT \"value\" FROM settings WHERE \"key\"=?", arrayOf(key))
        .use { if (it.moveToFirst()) it.getString(0) else default }
    @Synchronized fun updateSetting(key: String, value: String) {
        db.insertWithOnConflict("settings", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun rekey(password: String) { require(password.isNotEmpty()); db.changePassword(password) }
    @Synchronized fun validateImportedFile() {
        db.rawQuery("PRAGMA quick_check", emptyArray<String>()).use { cursor ->
            require(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) { "数据库文件不完整" }
        }
        db.rawQuery("PRAGMA foreign_key_check", emptyArray<String>()).use {
            require(!it.moveToFirst()) { "数据库关联数据不完整" }
        }
    }
    @Synchronized override fun close() = db.close()

    private fun nextOrder(table: String): Long = db.rawQuery("SELECT COALESCE(MAX(sort_order),-1)+1 FROM $table", emptyArray<String>())
        .use { it.moveToFirst(); it.getLong(0) }
    private fun <T> transaction(work: () -> T): T {
        db.beginTransaction()
        try {
            val result = work()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    companion object {
        const val MAX_ATTACHMENTS_PER_CARD = 8
        const val MAX_ATTACHMENT_BYTES_PER_FILE = 8 * 1024 * 1024
        const val MAX_ATTACHMENT_BYTES_PER_CARD = 24L * 1024 * 1024
        private const val VERSION = 3
        private const val APPLICATION_ID = 0x41554E31 // "AUN1"; rejects unrelated SQLite files.
        private val TOTP_COLORS = intArrayOf(0xff759488.toInt(), 0xff7695ad.toInt(), 0xffc08c79.toInt(), 0xffa68db7.toInt(), 0xffb59b68.toInt(), 0xff718c9d.toInt())
        init { System.loadLibrary("sqlcipher") }
        private val preserveCorruptDatabase = DatabaseErrorHandler { _, error -> throw error }

        fun open(file: File, password: String, create: Boolean = false): VaultDatabase {
            require(password.isNotEmpty()) { "主密码不能为空" }
            if (create) require(!file.exists()) { "数据库已经存在" }
            if (!create) require(file.isFile && file.length() > 0) { "数据库文件不存在或为空" }
            val flags = SQLiteDatabase.OPEN_READWRITE or if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0
            val db = SQLiteDatabase.openDatabase(file.absolutePath, password, null, flags, preserveCorruptDatabase, null)
            try {
                if (create) {
                    require(db.version == 0) { "数据库已经存在" }
                    db.beginTransaction()
                    try {
                        db.execSQL("CREATE TABLE account_cards (id INTEGER PRIMARY KEY AUTOINCREMENT,tag TEXT NOT NULL,create_time INTEGER NOT NULL,update_time INTEGER NOT NULL,color INTEGER NOT NULL,remark TEXT NOT NULL DEFAULT '',sort_order INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)))")
                        db.execSQL("CREATE TABLE card_fields (id INTEGER PRIMARY KEY AUTOINCREMENT,card_id INTEGER NOT NULL REFERENCES account_cards(id) ON DELETE CASCADE,name TEXT NOT NULL,value TEXT NOT NULL,sort_order INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX card_fields_card ON card_fields(card_id,sort_order)")
                        db.execSQL("CREATE TABLE card_attachments (id INTEGER PRIMARY KEY AUTOINCREMENT,card_id INTEGER NOT NULL REFERENCES account_cards(id) ON DELETE CASCADE,name TEXT NOT NULL,mime_type TEXT NOT NULL,content BLOB NOT NULL,sort_order INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE totp_accounts (id INTEGER PRIMARY KEY AUTOINCREMENT,issuer TEXT NOT NULL DEFAULT '',account TEXT NOT NULL DEFAULT '',secret TEXT NOT NULL,algorithm TEXT NOT NULL CHECK(algorithm IN ('SHA1','SHA256','SHA512')),digits INTEGER NOT NULL CHECK(digits IN (6,8)),period INTEGER NOT NULL CHECK(period>0 AND period<=86400),type TEXT NOT NULL DEFAULT 'TOTP' CHECK(type IN ('TOTP','HOTP')),counter INTEGER NOT NULL DEFAULT 0 CHECK(counter>=0),sort_order INTEGER NOT NULL,color INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE settings (\"key\" TEXT PRIMARY KEY,\"value\" TEXT NOT NULL)")
                        db.execSQL("PRAGMA application_id=$APPLICATION_ID")
                        db.version = VERSION
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
                validateIdentity(db)
                migrate(db)
                validateSchema(db)
                // A rollback journal exists only during writes; a closed export is one complete file.
                db.rawQuery("PRAGMA journal_mode=DELETE", emptyArray<String>()).use { check(it.moveToFirst() && it.getString(0).equals("delete", true)) }
                db.setForeignKeyConstraintsEnabled(true)
                db.execSQL("PRAGMA temp_store=MEMORY")
                db.execSQL("PRAGMA cipher_memory_security=ON")
                return VaultDatabase(db)
            } catch (error: Exception) { db.close(); throw error }
        }

        private fun validateSchema(db: SQLiteDatabase, version: Int = VERSION) {
            require(db.version == version) { "不支持的 AuthNotes 数据库版本" }
            mapOf(
                "account_cards" to setOf("id","tag","create_time","update_time","color","remark","sort_order","deleted"),
                "card_fields" to setOf("id","card_id","name","value","sort_order"),
                "card_attachments" to setOf("id","card_id","name","mime_type","content","sort_order"),
                "totp_accounts" to (setOf("id","issuer","account","secret","algorithm","digits","period","sort_order") +
                    (if (version >= 2) setOf("color") else emptySet()) + (if (version >= 3) setOf("type", "counter") else emptySet())),
                "settings" to setOf("key","value")
            ).forEach { (table, expected) ->
                val actual = db.rawQuery("PRAGMA table_info($table)", emptyArray<String>()).use { it.collect { c -> c.getString(1) }.toSet() }
                require(actual.containsAll(expected)) { "数据库结构不完整" }
            }
        }

        private fun validateIdentity(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA application_id", emptyArray<String>()).use {
                require(it.moveToFirst() && it.getInt(0) == APPLICATION_ID) { "请选择 AuthNotes 数据库" }
            }
        }

        private fun migrate(db: SQLiteDatabase) {
            if (db.version == 1) {
                validateSchema(db, 1)
                db.beginTransaction()
                try {
                    db.execSQL("ALTER TABLE totp_accounts ADD COLUMN color INTEGER NOT NULL DEFAULT ${TOTP_COLORS[0]}")
                    db.rawQuery("SELECT id FROM totp_accounts ORDER BY id", emptyArray<String>()).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            db.execSQL("UPDATE totp_accounts SET color=? WHERE id=?", arrayOf<Any>(TOTP_COLORS[Math.floorMod(id, TOTP_COLORS.size.toLong()).toInt()], id))
                        }
                    }
                    db.version = 2
                    validateSchema(db, 2)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            if (db.version == 2) {
                validateSchema(db, 2)
                db.beginTransaction()
                try {
                    db.execSQL("ALTER TABLE totp_accounts ADD COLUMN type TEXT NOT NULL DEFAULT 'TOTP' CHECK(type IN ('TOTP','HOTP'))")
                    db.execSQL("ALTER TABLE totp_accounts ADD COLUMN counter INTEGER NOT NULL DEFAULT 0 CHECK(counter>=0)")
                    db.version = VERSION
                    validateSchema(db, VERSION)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
    }
}

private inline fun <T> Cursor.collect(map: (Cursor) -> T): List<T> {
    val result = ArrayList<T>()
    while (moveToNext()) result += map(this)
    return result
}
