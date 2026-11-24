package com.authnotes.data

/** Persistent user settings boundary. Values remain stored in the encrypted vault. */
internal class SettingsRepository(private val database: () -> VaultDatabase) {
    fun get(key: String, default: String = "") = database().setting(key, default)
    fun set(key: String, value: String) = database().updateSetting(key, value)
}
