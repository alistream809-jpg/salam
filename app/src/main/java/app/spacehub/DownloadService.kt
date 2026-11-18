package app.spacehub

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

fun mb(b: Long): String = if (b >= 1073741824L) "%.2f GB".format(b / 1073741824.0) else "%.1f MB".format(b / 1048576.0)

class DownloadService : Service() {
    private val app get() = SpaceApp.I
    private val pool = Executors.newFixedThreadPool(3)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val names = ConcurrentHashMap<Long, String>()
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        startFg()
        val id = i?.getLongExtra("id", -1) ?: -1
        when (i?.action) {
            "start" -> start(id)
            "pause" -> jobs[id]?.let { it.reason = "paused"; it.stop = true }
            "cancel" -> {
                val j = jobs[id]
                if (j != null) { j.reason = "cancelled"; j.stop = true }
                else {
                    app.db.one("SELECT dest FROM downloads WHERE id=?", id.toString())?.let { File(it.getString("dest") + ".part").delete() }
                    app.db.setState(id, "cancelled", null); emitState(id)
                }
            }
        }
        if (jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }

    private fun start(id: Long) {
        if (jobs.containsKey(id)) return
        val job = Job(); jobs[id] = job
        pool.execute {
            try { run(id, job) }
            catch (e: Exception) { app.db.setState(id, "failed", "UNKNOWN"); emitState(id) }
            finally {
                jobs.remove(id); live.remove(id)
                if (jobs.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } else refreshFg()
            }
        }
    }

    private fun run(id: Long, job: Job) {
        val db = app.db
        val d = db.one("SELECT * FROM downloads WHERE id=?", id.toString()) ?: return
        val dest = File(d.getString("dest"))
        names[id] = d.getString("title")
        db.setState(id, "running", null); emitState(id)
        try {
            if (app.prefs.getBoolean("wifi_only", false) && !unmetered()) throw DlException("WIFI_ONLY")
            val gh = d.getString("kind") == "github"
            val headers = if (gh) Gh.authHeaders(this) else emptyMap()
            if (gh && headers.isEmpty()) throw DlException("TOKEN")
            var lastDb = 0L
            Downloader.fetch(d.getString("url"), dest, headers, job, d.optLong("total")) { b, t, bps ->
                live[id] = longArrayOf(b, t, bps)
                val now = SystemClock.elapsedRealtime()
                if (now - lastDb > 1000) { lastDb = now; db.setProgress(id, b, t); refreshFg() }
                app.emit("progress", JSONObject().put("id", id).put("bytes", b).put("total", t).put("bps", bps))
            }
            db.setProgress(id, dest.length().coerceAtLeast(1), dest.length().coerceAtLeast(1))
            val libId = finish(d, dest, id)
            db.setState(id, "done", null); emitState(id); notifyEnd(id, true)
            app.emit("library", JSONObject())
            if (app.prefs.getBoolean("auto_install", false) && packageManager.canRequestPackageInstalls()) Installer.install(this, libId)
        } catch (e: DlException) {
            if (e.code == "STOP") {
                if (job.reason == "cancelled") { File(dest.path + ".part").delete(); db.setState(id, "cancelled", null) }
                else db.setState(id, "paused", null)
            } else { db.setState(id, "failed", e.code); notifyEnd(id, false) }
            emitState(id)
        } catch (e: IOException) {
            db.setState(id, "failed", if (e.message?.contains("ENOSPC") == true) "STORAGE" else "NETWORK")
            notifyEnd(id, false); emitState(id)
        }
    }

    /** Integrity check + APK validation + library insert. Returns library id. */
    private fun finish(d: JSONObject, dest: File, id: Long): Long {
        val meta = JSONObject(d.getString("meta"))
        val github = d.getString("kind") == "github"
        val dir = File(dest.parentFile, "gh_$id")
        try {
            if (!github) {
                if (!Sha256.of(dest).equals(d.getString("sha256"), true)) { dest.delete(); throw DlException("HASH") }
                val set = Validator.analyze(this, listOf(dest))
                if (set.base.pkg != d.getString("pkg")) { dest.delete(); throw DlException("INVALID") }
                return Lib.add(set.base.pkg, meta.optString("name", set.base.label), set.base.verName, set.base.verCode,
                    dest.length(), meta.optString("icon").ifEmpty { null }, meta.optString("cover").ifEmpty { null },
                    meta.optString("cats").ifEmpty { null }, "fdroid", set.files)
            }
            val apks = Zip.extractApks(dest, dir)
            dest.delete()
            val set = Validator.analyze(this, apks)
            return Lib.add(set.base.pkg, set.base.label, set.base.verName, set.base.verCode, set.files.sumOf { it.length() },
                Validator.iconUri(this, set.base), null, null, "github", set.files)
        } catch (e: Validator.Fail) {
            if (github) dir.deleteRecursively()
            throw DlException(e.code)
        }
    }

    private fun unmetered(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun emitState(id: Long) {
        val r = app.db.one("SELECT id,state,error,pkg,title FROM downloads WHERE id=?", id.toString()) ?: return
        app.emit("download", r)
    }

    private fun openIntent() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun channels() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("dl", "SpaceHub", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel("done", "SpaceHub ", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun fgNotif(): Notification {
        val act = jobs.keys.toList()
        val b = NotificationCompat.Builder(this, "dl").setSmallIcon(R.drawable.ic_stat).setOngoing(true)
            .setOnlyAlertOnce(true).setContentIntent(openIntent())
        if (act.size <= 1) {
            val id = act.firstOrNull()
            val p = id?.let { live[it] }
            b.setContentTitle(id?.let { names[it] } ?: app.str("n_dl", "Downloading"))
            if (p != null && p[1] > 0) b.setProgress(100, (p[0] * 100 / p[1]).toInt(), false)
                .setContentText("${mb(p[0])} / ${mb(p[1])} • ${mb(p[2])}/s")
            else b.setProgress(0, 0, true)
        } else b.setContentTitle(app.str("n_many", "{n}").replace("{n}", act.size.toString())).setProgress(0, 0, true)
        return b.build()
    }

    private fun startFg() {
        channels()
        val n = fgNotif()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
    }

    private fun refreshFg() { if (jobs.isNotEmpty()) nm.notify(1, fgNotif()) }

    private fun notifyEnd(id: Long, ok: Boolean) {
        if (!app.prefs.getBoolean("notif", true)) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.notify(2000 + id.toInt(), NotificationCompat.Builder(this, "done").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(names[id] ?: "SpaceHub").setContentText(app.str(if (ok) "n_done" else "n_fail", if (ok) "OK" else "Failed"))
            .setAutoCancel(true).setContentIntent(openIntent()).build())
    }

    companion object {
        val live = ConcurrentHashMap<Long, LongArray>()
        fun send(ctx: Context, action: String, id: Long) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java).setAction(action).putExtra("id", id))
        }
    }
}
