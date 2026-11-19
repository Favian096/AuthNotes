package com.authnotes

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Exposes encrypted snapshots and short-lived in-memory attachment copies through read URI grants. */
class VaultShareProvider : ContentProvider() {
    override fun onCreate() = true
    private fun resolve(uri: Uri): File {
        val directory = File(requireNotNull(context).cacheDir, "shared-vaults").canonicalFile
        val name = uri.lastPathSegment.orEmpty()
        if (uri.pathSegments.size != 1 || !name.matches(Regex("AuthNotes-[0-9]+\\.db"))) throw FileNotFoundException()
        val file = File(directory, name).canonicalFile
        if (file.parentFile != directory || !file.isFile) throw FileNotFoundException()
        return file
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read access only")
        sharedAttachment(uri)?.let { attachment ->
            val pipe = ParcelFileDescriptor.createPipe()
            Thread({
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(attachment.content) } }
            }, "authnotes-attachment-share").apply { isDaemon = true; start() }
            return pipe[0]
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = sharedAttachment(uri)?.mimeType ?: "application/octet-stream"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        sharedAttachment(uri)?.let { attachment ->
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { when (it) {
                    OpenableColumns.DISPLAY_NAME -> attachment.name
                    OpenableColumns.SIZE -> attachment.content.size.toLong()
                    else -> null
                } })
            }
        }
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private fun sharedAttachment(uri: Uri): SharedAttachment? {
        if (uri.pathSegments.size != 2 || uri.pathSegments[0] != "attachments") return null
        val token = uri.pathSegments[1]
        if (!token.matches(Regex("[0-9a-f-]{36}"))) throw FileNotFoundException()
        return synchronized(sharedAttachments) {
            val now = System.currentTimeMillis()
            sharedAttachments.entries.removeAll { it.value.expiresAt < now }
            sharedAttachments[token]
        } ?: throw FileNotFoundException()
    }

    private data class SharedAttachment(val name: String, val mimeType: String, val content: ByteArray, val expiresAt: Long)

    companion object {
        private val sharedAttachments = linkedMapOf<String, SharedAttachment>()

        fun registerAttachment(name: String, mimeType: String, content: ByteArray): String = synchronized(sharedAttachments) {
            val now = System.currentTimeMillis()
            sharedAttachments.entries.removeAll { it.value.expiresAt < now }
            val token = UUID.randomUUID().toString()
            sharedAttachments[token] = SharedAttachment(name, mimeType, content.copyOf(), now + 10 * 60 * 1000L)
            while (sharedAttachments.size > 3) sharedAttachments.remove(sharedAttachments.keys.first())
            token
        }
    }
}
