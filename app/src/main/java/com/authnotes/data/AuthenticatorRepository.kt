package com.authnotes.data

/** OTP list and counter operations used by the authenticator page. */
internal class AuthenticatorRepository(private val database: () -> VaultDatabase) {
    fun list(query: String) = database().totpAccounts(query)
    fun all() = database().totpAccounts()
    fun reorder(ids: List<Long>) = database().reorderTotp(ids)
    fun advanceHotp(id: Long) = database().advanceHotpCounter(id)
    fun delete(id: Long) = database().deleteTotp(id)
    fun import(configs: List<com.authnotes.security.TotpConfig>, colors: List<Int>) = database().importTotp(configs, colors)
    fun save(issuer: String, account: String, secret: String, algorithm: String, digits: Int, period: Int,
        color: Int, type: String, counter: Long, id: Long?) =
        database().saveTotp(issuer, account, secret, algorithm, digits, period, color, type, counter, id)
}
