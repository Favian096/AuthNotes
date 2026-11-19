package com.authnotes.data

import android.content.Context
import android.net.Uri
import com.authnotes.security.MasterKeyVault
import com.authnotes.security.Totp
import com.authnotes.security.TotpConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Heavy operations run on the Activity's single I/O executor while the UI is blocked. */
class VaultStore(private val context: Context) {
    private val databaseFile = File(context.filesDir, "authnotes.db")
    private val previousFile = File(context.filesDir, "authnotes.rollback")
    private val stageFile = File(context.filesDir, "authnotes.pending")
    private val keys = MasterKeyVault(context.getSharedPreferences("local-key-envelope", Context.MODE_PRIVATE))
    var database: VaultDatabase? = null
        private set
    private var activePassword: String? = null
    val exists: Boolean get() = databaseFile.exists()

    fun autoUnlock(): Boolean {
        stageFile.delete()
        if (!exists) return false
        for (password in listOfNotNull(keys.load("pending"), keys.load()).distinct()) {
            val opened = runCatching { VaultDatabase.open(databaseFile, password) }.getOrNull() ?: continue
            database = opened
            activePassword = password
            runCatching { keys.save(password); keys.clearPending() }
            previousFile.delete()
            return true
        }
        return false
    }

    fun unlock(password: String): Boolean {
        val opened = VaultDatabase.open(databaseFile, password)
        database?.close()
        database = opened
        activePassword = password
        val autoUnlockReady = runCatching { keys.save(password); keys.clearPending() }.isSuccess
        previousFile.delete()
        return autoUnlockReady
    }

    fun create(password: String): Boolean {
        require(password.length >= 4) { "主密码至少需要 4 个字符" }
        require(!exists) { "数据库已经存在" }
        stageFile.delete()
        try {
            VaultDatabase.open(stageFile, password, create = true).close()
            return installStage(password)
        } finally { stageFile.delete() }
    }

    fun importDatabase(uri: Uri, password: String): Boolean {
        stageFile.delete()
        try {
            val source = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件" }
            source.use { input -> FileOutputStream(stageFile).use { output -> input.copyTo(output); output.fd.sync() } }
            // Existing-file mode: empty or unrelated SQLite files never become a new AuthNotes vault.
            VaultDatabase.open(stageFile, password).use { imported ->
                imported.validateImportedFile()
                imported.totpAccounts().forEach { account ->
                    Totp.code(TotpConfig(account.issuer, account.account, account.secret, account.algorithm,
                        account.digits, account.period, account.type, account.counter))
                }
            }
            return installStage(password)
        } finally { stageFile.delete() }
    }

    private fun installStage(password: String): Boolean {
        val oldPassword = activePassword ?: keys.load()
        val pendingSaved = runCatching { keys.save(password, "pending") }.isSuccess
        database?.close()
        database = null
        var replaced = false
        var backedUp = false
        try {
            if (databaseFile.exists()) {
                copySynced(databaseFile, previousFile)
                backedUp = true
            }
            // Same-directory atomic replacement: a crash leaves either complete old or complete new DB.
            Files.move(stageFile.toPath(), databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            replaced = true
            database = VaultDatabase.open(databaseFile, password)
            activePassword = password
            val currentSaved = runCatching { keys.save(password) }.isSuccess
            if (currentSaved) runCatching { keys.clearPending() }
            else if (!pendingSaved) runCatching { keys.clearPending() }
            previousFile.delete()
            return currentSaved || pendingSaved
        } catch (error: Exception) {
            database?.close()
            database = null
            if (replaced) {
                if (backedUp) {
                    Files.move(previousFile.toPath(), databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    databaseFile.delete()
                }
            }
            if (oldPassword != null && (!replaced || backedUp)) {
                database = VaultDatabase.open(databaseFile, oldPassword)
                activePassword = oldPassword
                keys.save(oldPassword)
                keys.clearPending()
            } else if (oldPassword == null || (replaced && !backedUp)) {
                runCatching { keys.clearPending() }
            }
            throw error
        }
    }

    fun changePassword(password: String) {
        require(password.length >= 4)
        val pendingSaved = runCatching { keys.save(password, "pending") }.isSuccess
        try { database!!.rekey(password) }
        catch (error: Exception) {
            // Current remains the valid password if SQLCipher rejected the rekey.
            if (pendingSaved) runCatching { keys.clearPending() }
            throw error
        }
        activePassword = password
        // The password has changed even if local key storage is unavailable; manual unlock remains available.
        val currentSaved = runCatching { keys.save(password) }.isSuccess
        if (currentSaved || !pendingSaved) runCatching { keys.clearPending() }
    }

    fun exportSnapshot(): File {
        val password = requireNotNull(activePassword) { "请重新输入数据主密码" }
        val directory = File(context.cacheDir, "shared-vaults").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000L }?.forEach { it.delete() }
        val snapshot = File(directory, "AuthNotes-${System.currentTimeMillis()}.db")
        database!!.close()
        database = null
        try { copySynced(databaseFile, snapshot) }
        catch (error: Exception) { snapshot.delete(); throw error }
        finally { database = VaultDatabase.open(databaseFile, password) }
        return snapshot
    }

    private fun copySynced(from: File, to: File) {
        from.inputStream().use { input -> FileOutputStream(to).use { output -> input.copyTo(output); output.fd.sync() } }
    }
    fun close() { database?.close(); database = null; activePassword = null }
}
