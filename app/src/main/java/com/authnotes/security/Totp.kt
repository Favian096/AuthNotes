package com.authnotes.security

import android.net.Uri
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TotpConfig(val issuer: String, val account: String, val secret: String,
    val algorithm: String = "SHA1", val digits: Int = 6, val period: Int = 30,
    val type: String = "TOTP", val counter: Long = 0L)

/** RFC 4648 Base32 and RFC 6238 TOTP, using Android's cryptographic primitives. */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun normalizeSecret(input: String): String {
        val normalized = input.uppercase(Locale.ROOT).filterNot { it.isWhitespace() || it == '-' }
        val clean = normalized.trimEnd('=')
        require(clean.isNotEmpty() && clean.all { it in ALPHABET }) { "请输入有效的 Base32 密钥（A–Z、2–7）" }
        require(clean.length % 8 in setOf(0, 2, 4, 5, 7)) { "Base32 密钥长度有误" }
        if (normalized != clean) require(normalized.length % 8 == 0 && normalized.length - clean.length in 1..6) { "Base32 填充格式有误" }
        return clean
    }

    fun decodeSecret(input: String): ByteArray {
        val clean = normalizeSecret(input)
        var buffer = 0
        var bits = 0
        val output = ArrayList<Byte>()
        clean.forEach { char ->
            buffer = (buffer shl 5) or ALPHABET.indexOf(char)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output += ((buffer shr bits) and 255).toByte()
            }
        }
        require(bits == 0 || (buffer and ((1 shl bits) - 1)) == 0) { "Base32 密钥末尾无效" }
        return output.toByteArray()
    }

    fun encodeSecret(input: ByteArray): String {
        require(input.isNotEmpty()) { "密钥不能为空" }
        val output = StringBuilder((input.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        input.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(ALPHABET[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) output.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return output.toString()
    }

    fun code(config: TotpConfig, timeMillis: Long = System.currentTimeMillis()): String {
        return when (config.type) {
            "TOTP" -> {
                require(config.period in 1..86400 && timeMillis >= 0) { "TOTP 周期须在 1–86400 秒内" }
                codeAt(config, timeMillis / 1000L / config.period)
            }
            "HOTP" -> hotp(config, config.counter)
            else -> throw IllegalArgumentException("不支持的认证类型")
        }
    }

    fun hotp(config: TotpConfig, counter: Long): String {
        require(counter >= 0) { "HOTP 计数器不能为负数" }
        return codeAt(config, counter)
    }

    private fun codeAt(config: TotpConfig, counter: Long): String {
        require(config.digits == 6 || config.digits == 8) { "验证码须为 6 位或 8 位" }
        require(config.algorithm in setOf("SHA1", "SHA256", "SHA512")) { "不支持的认证算法" }
        val mac = Mac.getInstance("Hmac${config.algorithm}")
        val key = decodeSecret(config.secret)
        try { mac.init(SecretKeySpec(key, mac.algorithm)) } finally { key.fill(0) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 15
        val binary = ((hash[offset].toInt() and 127) shl 24) or
            ((hash[offset + 1].toInt() and 255) shl 16) or
            ((hash[offset + 2].toInt() and 255) shl 8) or (hash[offset + 3].toInt() and 255)
        return (binary % if (config.digits == 6) 1000000 else 100000000).toString().padStart(config.digits, '0')
    }

    fun parseUri(value: String): TotpConfig {
        val uri = Uri.parse(value.trim())
        require(uri.scheme == "otpauth" && uri.host in setOf("totp", "hotp") && uri.isHierarchical) { "仅支持 TOTP 或 HOTP 认证二维码" }
        val type = if (uri.host == "hotp") "HOTP" else "TOTP"
        val label = uri.lastPathSegment.orEmpty()
        val issuerFromLabel = if (':' in label) label.substringBefore(':').trim() else ""
        val account = if (':' in label) label.substringAfter(':').trim() else label
        val params = uri.queryParameterNames.associateWith { name ->
            val values = uri.getQueryParameters(name)
            require(values.size == 1) { "二维码包含重复参数" }
            values.single()
        }
        val secret = normalizeSecret(params["secret"].orEmpty())
        val digits = params["digits"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("验证码位数无效") } ?: 6
        val period = if (type == "HOTP") 30 else
            params["period"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("认证周期无效") } ?: 30
        val counter = if (type == "HOTP") params["counter"]?.toLongOrNull()
            ?: throw IllegalArgumentException("HOTP 二维码缺少有效计数器") else 0L
        val algorithm = (params["algorithm"] ?: "SHA1").uppercase(Locale.ROOT)
        val config = TotpConfig(params["issuer"].orEmpty().ifBlank { issuerFromLabel }, account, secret, algorithm, digits, period, type, counter)
        code(config)
        return config
    }
}
