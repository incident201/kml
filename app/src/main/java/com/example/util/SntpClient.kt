package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object SntpClient {

    private const val MIN_ACCEPTED_TIME = 1704067200000L // 2024-01-01 00:00:00 UTC
    private const val MAX_ACCEPTED_TIME = 4102444800000L // 2100-01-01 00:00:00 UTC

    private val NTP_SERVERS = listOf(
        "time.google.com",
        "time.cloudflare.com",
        "pool.ntp.org",
        "time.windows.com"
    )

    private val HTTPS_TIME_URLS = listOf(
        "https://www.google.com/generate_204",
        "https://www.cloudflare.com/",
        "https://www.microsoft.com/"
    )

    suspend fun getCurrentTimeUtc(): Long? = withContext(Dispatchers.IO) {
        // 1. Try NTP servers in parallel
        val ntpJobs = NTP_SERVERS.map { server ->
            async {
                withTimeoutOrNull(2500) {
                    queryNtpServer(server)
                }
            }
        }
        val ntpResults = ntpJobs.mapNotNull { it.await() }
        if (ntpResults.isNotEmpty()) {
            return@withContext getLowerMedian(ntpResults)
        }

        // 2. Try HTTPS headers fallback in parallel
        val httpsJobs = HTTPS_TIME_URLS.map { url ->
            async {
                withTimeoutOrNull(3000) {
                    queryHttpsDate(url)
                }
            }
        }
        val httpsResults = httpsJobs.mapNotNull { it.await() }
        if (httpsResults.isNotEmpty()) {
            return@withContext getLowerMedian(httpsResults)
        }

        null
    }

    private fun queryNtpServer(server: String): Long? {
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(server)
            socket = DatagramSocket()
            socket.soTimeout = 2500
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
            return if (ntpTimeMilliseconds in MIN_ACCEPTED_TIME..MAX_ACCEPTED_TIME) ntpTimeMilliseconds else null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            socket?.close()
        }
    }

    private fun queryHttpsDate(urlString: String): Long? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.instanceFollowRedirects = true
            
            val code = connection.responseCode
            if (code in 200..399) {
                val dateValue = connection.getHeaderFieldDate("Date", -1L)
                if (dateValue != -1L && dateValue in MIN_ACCEPTED_TIME..MAX_ACCEPTED_TIME) {
                    return dateValue
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection?.disconnect()
        }
        return null
    }

    private fun getLowerMedian(times: List<Long>): Long {
        val sorted = times.sorted()
        val index = (sorted.size - 1) / 2
        return sorted[index]
    }
}
