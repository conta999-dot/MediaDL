package jp.personal.mediadl.data

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    fun open(url: String, method: String = "GET"): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "ja,en;q=0.8")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                current = URL(URL(current), loc).toString()
            } else return c
        }
        throw java.io.IOException("リダイレクトが多すぎます")
    }

    fun getText(url: String, limit: Int = 2_000_000): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { readLimited(it, limit) }
        } finally { c.disconnect() }
    }

    /** Content-Length を取得（不明なら 0） */
    fun contentLength(url: String): Long = try {
        val c = open(url, "HEAD")
        try { if (c.responseCode in 200..299) c.contentLengthLong.coerceAtLeast(0) else 0 } finally { c.disconnect() }
    } catch (e: Exception) { 0 }

    private fun readLimited(s: InputStream, limit: Int): String {
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(16 * 1024)
        while (buf.size() < limit) {
            val n = s.read(b); if (n < 0) break
            buf.write(b, 0, n)
        }
        return buf.toString("UTF-8")
    }
}
