package app.spacehub

import android.os.SystemClock
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DlException(val code: String) : Exception(code)

class Job {
    @Volatile var stop = false
    @Volatile var reason = ""
}

/**
 * Resumable HTTPS downloader. Every number reported to the UI is measured from bytes actually read:
 * progress = bytes written, speed = bytes over a sliding 3 s window, ETA is derived from that speed.
 */
object Downloader {
    private const val MARGIN = 20L * 1024 * 1024

    fun fetch(url: String, dest: File, headers: Map<String, String>, job: Job, hint: Long, progress: (Long, Long, Long) -> Unit) {
        try { fetchInner(url, dest, headers, job, hint, progress) }
        catch (e: DlException) { throw e }
        catch (e: IOException) { throw DlException(if (e.message?.contains("ENOSPC") == true) "STORAGE" else "NETWORK") }
    }

    private fun fetchInner(url: String, dest: File, headers: Map<String, String>, job: Job, hint: Long, progress: (Long, Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var existing = if (part.exists()) part.length() else 0L
        var c = open(url, headers, existing)
        try {
            var code = c.responseCode
            if (code == 416) { // our partial file is stale/invalid: restart from zero
                part.delete(); existing = 0; c.disconnect()
                c = open(url, headers, 0); code = c.responseCode
            }
            when (code) {
                401, 403 -> throw DlException("AUTH")
                404, 410 -> throw DlException("GONE")
                200 -> if (existing > 0) { existing = 0; part.delete() } // server ignored Range
                206 -> {}
                else -> throw DlException("HTTP")
            }
            val total = if (code == 206)
                (c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: (existing + c.contentLengthLong))
            else c.contentLengthLong
            val need = (if (total > 0) total else hint) - existing
            val free = StatFs(dest.parentFile!!.path).availableBytes
            if (need > 0 && free < need + MARGIN) throw DlException("STORAGE")

            val samples = ArrayDeque<LongArray>()
            var done = existing
            var last = 0L
            c.inputStream.use { ins ->
                FileOutputStream(part, existing > 0).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        if (job.stop) throw DlException("STOP")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - last >= 400) {
                            last = now
                            samples.addLast(longArrayOf(now, done))
                            while (samples.size > 1 && now - samples.first()[0] > 3000) samples.removeFirst()
                            val f = samples.first()
                            val bps = if (now > f[0]) (done - f[1]) * 1000 / (now - f[0]) else 0L
                            progress(done, total, bps)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw DlException("NETWORK") // truncated: keep .part for resume
            progress(done, total, 0)
            if (!part.renameTo(dest)) { dest.delete(); if (!part.renameTo(dest)) throw DlException("STORAGE") }
        } finally { c.disconnect() }
    }

    /** HTTPS only. Redirects are followed manually so auth headers never leave the original host. */
    private fun open(start: String, headers: Map<String, String>, from: Long): HttpURLConnection {
        var url = URL(start)
        val origin = url.host
        repeat(6) {
            if (url.protocol != "https") throw DlException("INSECURE")
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "SpaceHub/1.0")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (url.host == origin) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 301..308 && code != 304) {
                val loc = c.getHeaderField("Location") ?: throw DlException("NETWORK")
                url = URL(url, loc); c.disconnect()
            } else return c
        }
        throw DlException("NETWORK")
    }
}
