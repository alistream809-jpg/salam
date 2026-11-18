package app.spacehub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Installs through PackageInstaller sessions. Android always shows its own confirmation dialog; nothing is silent. */
object Installer {
    const val ACTION = "app.spacehub.INSTALL_RESULT"

    /** Returns null when the session was committed (result arrives via [InstallReceiver]) or an error code. */
    fun install(ctx: Context, libId: Long): String? {
        val db = SpaceApp.I.db
        val row = db.one("SELECT * FROM library WHERE id=?", libId.toString()) ?: return "CORRUPT"
        val files = row.getString("files").split("|").map { File(it) }
        if (files.any { !it.exists() }) return "CORRUPT"
        val set = try { Validator.analyze(ctx, files) } catch (e: Validator.Fail) { return e.code }
        if (!ctx.packageManager.canRequestPackageInstalls()) return "NEED_PERM"

        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(set.base.pkg)
        params.setSize(set.files.sumOf { it.length() })
        var sid = -1
        try {
            sid = pi.createSession(params)
            pi.openSession(sid).use { s ->
                for (f in set.files) {
                    f.inputStream().use { ins ->
                        s.openWrite(f.name, 0, f.length()).use { out -> ins.copyTo(out); s.fsync(out) }
                    }
                }
                val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION).putExtra("libId", libId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pend = PendingIntent.getBroadcast(ctx, sid, intent, flags)
                db.exec("UPDATE library SET pending=1 WHERE id=?", libId)
                s.commit(pend.intentSender)
            }
        } catch (e: Exception) {
            if (sid >= 0) try { pi.abandonSession(sid) } catch (_: Exception) {}
            db.exec("UPDATE library SET pending=0 WHERE id=?", libId)
            return "INSTFAIL"
        }
        return null
    }
}

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)

    override fun onReceive(c: Context, i: Intent) {
        val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val id = i.getLongExtra("libId", -1)
        val app = SpaceApp.I
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirmIntent(i)?.let { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(it) }
        } else {
            app.db.exec("UPDATE library SET pending=0 WHERE id=?", id)
            app.emit("install", JSONObject().put("id", id).put("status", st))
        }
    }
}
