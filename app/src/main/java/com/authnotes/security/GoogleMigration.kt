package com.authnotes.security

import android.net.Uri
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** Google Authenticator's otpauth-migration wire format without a protobuf runtime dependency. */
object GoogleMigration {
    private const val MAX_PAYLOAD_BYTES = 64 * 1024
    private const val ACCOUNTS_PER_QR = 3

    fun isMigrationUri(value: String): Boolean {
        val uri = Uri.parse(value.trim())
        return uri.scheme == "otpauth-migration" && uri.host == "offline"
    }

    fun decodeUri(value: String): List<TotpConfig> {
        val uri = Uri.parse(value.trim())
        require(uri.scheme == "otpauth-migration" && uri.host == "offline" && uri.isHierarchical) {
            "不是 Google Authenticator 迁移二维码"
        }
        val dataParameters = uri.getQueryParameters("data")
        require(dataParameters.size == 1) { "迁移二维码必须包含唯一的数据字段" }
        val encoded = dataParameters.single()
        require(encoded.length <= MAX_PAYLOAD_BYTES * 2) { "迁移二维码数据过大" }
        val normalized = encoded.replace(' ', '+').replace('-', '+').replace('_', '/')
        require(normalized.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }) {
            "迁移二维码 Base64 数据无效"
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = Base64.decode(padded, Base64.DEFAULT)
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) { "迁移二维码数据无效" }
        return parsePayload(bytes)
    }

    fun encodeUris(configs: List<TotpConfig>): List<String> {
        require(configs.isNotEmpty()) { "请选择至少一个认证账户" }
        configs.forEach(::validateConfig)
        val batches = configs.chunked(ACCOUNTS_PER_QR)
        val batchId = SecureRandom().nextInt(Int.MAX_VALUE)
        return batches.mapIndexed { index, accounts ->
            val payload = encodePayload(accounts, batches.size, index, batchId)
            val data = Base64.encodeToString(payload, Base64.NO_WRAP)
            Uri.Builder().scheme("otpauth-migration").authority("offline")
                .appendQueryParameter("data", data).build().toString()
        }
    }

    private fun parsePayload(bytes: ByteArray): List<TotpConfig> {
        val reader = ProtoReader(bytes)
        val accounts = mutableListOf<TotpConfig>()
        var version = 0L
        var batchSize = 0L
        var batchIndex = 0L
        while (reader.hasRemaining()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            require(field > 0) { "迁移二维码字段无效" }
            when (field) {
                1 -> { require(wire == 2); accounts += parseAccount(reader.readBytes()) }
                2 -> { require(wire == 0); version = reader.readVarint() }
                3 -> { require(wire == 0); batchSize = reader.readVarint() }
                4 -> { require(wire == 0); batchIndex = reader.readVarint() }
                else -> reader.skip(wire)
            }
        }
        require(version in 0L..1L) { "不支持的迁移二维码版本" }
        if (batchSize > 0) require(batchSize <= 100 && batchIndex >= 0L && batchIndex < batchSize) { "迁移二维码批次信息无效" }
        require(accounts.size <= 50) { "单张迁移二维码账户数量过多" }
        require(accounts.isNotEmpty()) { "迁移二维码中没有认证账户" }
        return accounts
    }

    private fun parseAccount(bytes: ByteArray): TotpConfig {
        val reader = ProtoReader(bytes)
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = "SHA1"
        var digits = 6
        var type = "TOTP"
        var counter = 0L
        while (reader.hasRemaining()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wire = tag and 7
            require(field > 0) { "认证账户字段无效" }
            when (field) {
                1 -> { require(wire == 2); secret = reader.readBytes() }
                2 -> { require(wire == 2); name = reader.readString() }
                3 -> { require(wire == 2); issuer = reader.readString() }
                4 -> {
                    require(wire == 0)
                    algorithm = when (reader.readVarint().toInt()) {
                        0, 1 -> "SHA1"
                        2 -> "SHA256"
                        3 -> "SHA512"
                        else -> throw IllegalArgumentException("迁移账户使用了不支持的算法")
                    }
                }
                5 -> {
                    require(wire == 0)
                    digits = when (reader.readVarint().toInt()) {
                        0, 1 -> 6
                        2 -> 8
                        else -> throw IllegalArgumentException("迁移账户位数无效")
                    }
                }
                6 -> {
                    require(wire == 0)
                    type = when (reader.readVarint().toInt()) {
                        0, 2 -> "TOTP"
                        1 -> "HOTP"
                        else -> throw IllegalArgumentException("迁移账户类型无效")
                    }
                }
                7 -> { require(wire == 0); counter = reader.readVarint() }
                else -> reader.skip(wire)
            }
        }
        val key = secret ?: throw IllegalArgumentException("迁移账户缺少密钥")
        val inferredIssuer = issuer.ifBlank { name.substringBefore(':').takeIf { ':' in name }.orEmpty() }
        val account = if (inferredIssuer.isNotBlank() && name.startsWith("$inferredIssuer:")) name.substringAfter(':') else name
        val config = TotpConfig(inferredIssuer, account, Totp.encodeSecret(key), algorithm, digits, 30, type, counter)
        validateConfig(config)
        return config
    }

    private fun encodePayload(accounts: List<TotpConfig>, batchSize: Int, batchIndex: Int, batchId: Int): ByteArray {
        val writer = ProtoWriter()
        accounts.forEach { writer.bytes(1, encodeAccount(it)) }
        writer.varint(2, 1)
        writer.varint(3, batchSize.toLong())
        writer.varint(4, batchIndex.toLong())
        writer.varint(5, batchId.toLong())
        return writer.toByteArray()
    }

    private fun encodeAccount(config: TotpConfig): ByteArray {
        validateConfig(config)
        val writer = ProtoWriter()
        writer.bytes(1, Totp.decodeSecret(config.secret))
        writer.string(2, config.account.ifBlank { config.issuer })
        if (config.issuer.isNotBlank()) writer.string(3, config.issuer)
        writer.varint(4, when (config.algorithm) { "SHA1" -> 1; "SHA256" -> 2; "SHA512" -> 3; else -> error("不支持的算法") })
        writer.varint(5, if (config.digits == 8) 2 else 1)
        writer.varint(6, if (config.type == "HOTP") 1 else 2)
        if (config.type == "HOTP") writer.varint(7, config.counter)
        return writer.toByteArray()
    }

    private fun validateConfig(config: TotpConfig) {
        require(config.issuer.isNotBlank() || config.account.isNotBlank()) { "迁移账户缺少标签或账户名" }
        require(config.issuer.length <= 128 && config.account.length <= 128) { "账户标签过长，无法安全放入迁移二维码" }
        require(config.type == "TOTP" || config.type == "HOTP") { "迁移账户类型无效" }
        require(config.type != "TOTP" || config.period == 30) { "Google Authenticator 迁移格式仅支持 30 秒 TOTP 周期" }
        require(config.algorithm in setOf("SHA1", "SHA256", "SHA512")) { "迁移账户算法无效" }
        require(config.digits == 6 || config.digits == 8) { "迁移账户位数无效" }
        require(config.counter >= 0L) { "HOTP 计数器无效" }
        Totp.code(config)
    }

    private class ProtoWriter {
        private val output = java.io.ByteArrayOutputStream()
        fun varint(field: Int, value: Long) { tag(field, 0); rawVarint(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray(StandardCharsets.UTF_8))
        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2); rawVarint(value.size.toLong()); output.write(value)
        }
        private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())
        private fun rawVarint(value: Long) {
            var remaining = value
            while ((remaining and -128L) != 0L) {
                output.write(((remaining.toInt() and 0x7f) or 0x80))
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
        fun toByteArray() = output.toByteArray()
    }

    private class ProtoReader(private val input: ByteArray) {
        private var offset = 0
        fun hasRemaining() = offset < input.size
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(offset < input.size) { "迁移二维码数据不完整" }
                val value = input[offset++].toInt() and 0xff
                if (shift == 63) require(value <= 1) { "迁移二维码整数格式无效" }
                result = result or ((value and 0x7f).toLong() shl shift)
                if ((value and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("迁移二维码整数格式无效")
        }
        fun readBytes(): ByteArray {
            val length = readVarint()
            require(length in 0..(input.size - offset).toLong()) { "迁移二维码长度无效" }
            val end = offset + length.toInt()
            return input.copyOfRange(offset, end).also { offset = end }
        }
        fun readString() = String(readBytes(), StandardCharsets.UTF_8)
        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> advance(8)
                2 -> { val size = readVarint(); require(size <= Int.MAX_VALUE); advance(size.toInt()) }
                5 -> advance(4)
                else -> throw IllegalArgumentException("迁移二维码字段格式无效")
            }
        }
        private fun advance(size: Int) {
            require(size >= 0 && size <= input.size - offset) { "迁移二维码数据不完整" }
            offset += size
        }
    }
}
