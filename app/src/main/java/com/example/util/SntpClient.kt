package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object SntpClient {
    suspend fun getCurrentTimeUtc(): Long? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            val ntpServer = "pool.ntp.org"
            val address = InetAddress.getByName(ntpServer)
            socket = DatagramSocket()
            socket.soTimeout = 5000
            val buffer = ByteArray(48)
            buffer[0] = 0x1B // NTP string request (version 3, mode 3)
            val request = DatagramPacket(buffer, buffer.size, address, 123)
            socket.send(request)
            
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            
            val offset = 40
            var seconds = 0L
            for (i in 0..3) {
                seconds = (seconds shl 8) or (buffer[offset + i].toLong() and 0xffL)
            }
            var fraction = 0L
            for (i in 4..7) {
                fraction = (fraction shl 8) or (buffer[offset + i].toLong() and 0xffL)
            }
            val ntpTimeMilliseconds = ((seconds - 2208988800L) * 1000) + ((fraction * 1000L) / 0x100000000L)
            ntpTimeMilliseconds
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            socket?.close()
        }
    }
}
