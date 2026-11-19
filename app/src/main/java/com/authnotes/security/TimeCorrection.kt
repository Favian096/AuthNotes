package com.authnotes.security

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import kotlin.math.abs

/** SNTP is used only when explicitly requested. A configured offset never changes system time. */
object TimeCorrection {
    fun offsetMillis(host: String): Long {
        val address = InetAddress.getByName(host)
        val request = ByteArray(48)
        request[0] = 0x23 // NTP v4 client
        val sentAt = System.currentTimeMillis()
        val start = SystemClock.elapsedRealtime()
        val seconds = sentAt / 1000L + 2208988800L
        putWord(request, 40, seconds)
        putWord(request, 44, (sentAt % 1000L) * 0x100000000L / 1000L)
        // Random low fraction bits let us verify that a reply belongs to this request.
        request[47] = SecureRandom().nextInt(256).toByte()
        val response = ByteArray(512)
        DatagramSocket().use { socket ->
            socket.soTimeout = 5000
            socket.connect(address, 123)
            socket.send(DatagramPacket(request, request.size))
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            val receivedAt = sentAt + (SystemClock.elapsedRealtime() - start)
            require(packet.length >= 48 && (response[0].toInt() and 7) == 4) { "时间服务器响应无效" }
            require(((response[0].toInt() ushr 6) and 3) != 3 && (response[1].toInt() and 255) in 1..15) { "时间服务器尚未同步" }
            require((0..7).all { response[24 + it] == request[40 + it] }) { "时间请求不匹配" }
            val serverReceive = readTime(response, 32, sentAt)
            val serverTransmit = readTime(response, 40, sentAt)
            val delay = (receivedAt - sentAt) - (serverTransmit - serverReceive)
            require(delay in -100L..5000L) { "时间请求延迟过大，请重试" }
            val offset = ((serverReceive - sentAt) + (serverTransmit - receivedAt)) / 2L
            require(abs(offset) <= 86400000L) { "系统时间偏差超过一天，请先校准手机时间" }
            return offset
        }
    }
    private fun putWord(bytes: ByteArray, offset: Int, word: Long) {
        for (i in 0..3) bytes[offset + i] = (word ushr (24 - 8 * i)).toByte()
    }
    private fun word(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 255L) }
    private fun readTime(bytes: ByteArray, offset: Int, now: Long): Long {
        val seconds = word(bytes, offset)
        val fraction = word(bytes, offset + 4)
        require(seconds != 0L || fraction != 0L)
        // Select the NTP era nearest the device time (handles the 2036 rollover).
        val era = ((now / 1000L + 2208988800L - seconds) + 0x80000000L) / 0x100000000L
        return (seconds + era * 0x100000000L - 2208988800L) * 1000L + fraction * 1000L / 0x100000000L
    }
}
