package app.spacehub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class Err(val code: String) : Exception(code)

/** Single entry point for the HTML/JS UI: call(id, method, jsonArgs) -> window.__res(id, json). */
class Bridge(private val act: MainActivity, private val web: WebView) {
    private val pool = Executors.newCachedThreadPool()
    private val app = SpaceApp.I
    private val db get() = app.db
    private val prefs get() = app.prefs

    @JavascriptInterface
    fun call(id: Int, method: String, args: String) {
        pool.execute {
            val res = try {
                JSONObject().put("ok", true).put("data", dispatch(method, JSONObject(args.ifEmpty { "{}" })) ?: JSONObject.NULL)
            } catch (e: Err) { JSONObject().put("ok", false).put("error", e.code) }
            catch (e: Gh.GhErr) { JSONObject().put("ok", false).put("error", e.code) }
            catch (e: Exception) { JSONObject().put("ok", false).put("error", "UNKNOWN") }
            act.runOnUiThread { web.evaluateJavascript("window.__res($id,${JSONObject.quote(res.toString())})", null) }
        }
    }

    private fun dispatch(m: String, a: JSONObject): Any? = when (m) {
        "state" -> state()
        "setPref" -> { val k = a.getString("key"); when (val v = a.get("value")) { is Boolean -> prefs.edit().putBoolean(k, v).apply(); else -> prefs.edit().putString(k, v.toString()).apply() }; null }
        "setStrings" -> { prefs.edit().putString("native_strings", a.toString()).apply(); null }
        "notifPerm" -> { act.askNotif(); null }
        "catalogHome" -> home()
        "catalogQuery" -> query(a)
        "catalogRefresh" -> { refreshCatalog(); null }
        "download" -> catalogDownload(a.getString("pkg"))
        "downloads" -> downloads()
        "dlAction" -> { DownloadService.send(act, a.getString("action"), a.getLong("id")); null }
        "dlRemove" -> {
            val id = a.getLong("id")
            db.one("SELECT dest,state FROM downloads WHERE id=?", id.toString())?.let {
                if (it.getString("state") !in listOf("running", "queued")) { File(it.getString("dest") + ".part").delete(); db.exec("DELETE FROM downloads WHERE id=?", id) }
            }; null
        }
        "library" -> Lib.list(act)
        "install" -> { Installer.install(act, a.getLong("id"))?.let { throw Err(it) }; true }
        "installSettings" -> { launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))); null }
        "play" -> play(a.getString("pkg"), a.optBoolean("force"))
        "appInfo" -> { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.getString("pkg")}"))); null }
        "libRemove" -> { Lib.remove(a.getLong("id")); null }
        "clearFiles" -> {
            for (r in db.rows("SELECT id,pkg,ver_code FROM library WHERE pending=0").objs())
                if (Pm.code(act, r.getString("pkg")) < r.getLong("ver_code")) Lib.remove(r.getLong("id"))
            null
        }
        "openUrl" -> { val u = Uri.parse(a.getString("url")); if (u.scheme == "https") launch(Intent(Intent.ACTION_VIEW, u)); null }
        "gh.hasToken" -> Gh.token(act) != null
        "gh.setToken" -> { Gh.setToken(act, a.optString("token")); null }
        "gh.workflows" -> {
            val (o, r, wf) = Gh.parse(a.getString("repo")) ?: throw Err("BADREPO")
            JSONObject().put("repo", "$o/$r").put("selected", wf ?: JSONObject.NULL).put("workflows", Gh.workflows(act, o, r))
        }
        "gh.runs" -> { val (o, r, _) = Gh.parse(a.getString("repo")) ?: throw Err("BADREPO"); Gh.runs(act, o, r, a.optString("workflow")) }
        "gh.artifacts" -> { val (o, r, _) = Gh.parse(a.getString("repo")) ?: throw Err("BADREPO"); Gh.artifacts(act, o, r, a.getLong("run")) }
        "gh.download" -> ghDownload(a)
        "perf" -> Perf.snapshot(act)
        "perf.games" -> perfGames()
        "perf.setPre" -> { prefs.edit().putBoolean("pre_${a.getString("pkg")}", a.getBoolean("value")).apply(); null }
        "perf.shortcut" -> shortcut(a.getString("pkg"))
        "perf.settings" -> {
            val act2 = when (a.getString("kind")) {
                "battery" -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                else -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            }
            launch(Intent(act2)); null
        }
        else -> throw Err("UNKNOWN")
    }

    private fun launch(i: Intent) { act.runOnUiThread { try { act.startActivity(i) } catch (e: Exception) {} } }

    private fun state(): JSONObject {
        val pi = act.packageManager.getPackageInfo(act.packageName, 0)
        val apkBytes = app.apkDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return JSONObject().put("lang", prefs.getString("lang", null) ?: JSONObject.NULL)
            .put("theme", prefs.getString("theme", "nebula")).put("wifi_only", prefs.getBoolean("wifi_only", false))
            .put("auto_install", prefs.getBoolean("auto_install", false)).put("notif", prefs.getBoolean("notif", true))
            .put("pre_default", prefs.getBoolean("pre_default", true)).put("name", prefs.getString("name", "") ?: "")
            .put("version", pi.versionName).put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
            .put("catalogCount", db.one("SELECT COUNT(*) c FROM catalog")?.getLong("c") ?: 0L)
            .put("catalogAt", prefs.getLong("catalog_at", 0L)).put("apkBytes", apkBytes)
            .put("canInstall", act.packageManager.canRequestPackageInstalls())
            .put("notifGranted", Build.VERSION.SDK_INT < 33 || act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    private fun refreshCatalog() {
        if (Catalog.running) return
        Thread {
            app.emit("catalog", JSONObject().put("stage", "start"))
            val err = Catalog.refresh { n -> app.emit("catalog", JSONObject().put("stage", "count").put("n", n)) }
            app.emit("catalog", JSONObject().put("stage", "done").put("error", err ?: JSONObject.NULL))
        }.start()
    }

    private fun annotate(rows: JSONArray): JSONArray {
        val libs = db.rows("SELECT id,pkg,ver_code FROM library").objs()
        val dls = db.rows("SELECT id,pkg FROM downloads WHERE state IN ('running','queued','paused','failed') AND pkg IS NOT NULL").objs()
        for (g in rows.objs()) {
            val pkg = g.getString("pkg"); val vc = g.getLong("ver_code"); val ic = Pm.code(act, pkg)
            val lib = libs.firstOrNull { it.getString("pkg") == pkg && it.getLong("ver_code") >= vc }
            val dl = dls.firstOrNull { it.getString("pkg") == pkg }
            g.put("installedCode", ic)
            when {
                ic >= vc -> g.put("state", "installed")
                dl != null -> g.put("state", "downloading").put("dlId", dl.getLong("id"))
                lib != null -> g.put("state", "downloaded").put("libId", lib.getLong("id"))
                ic >= 0 -> g.put("state", "update")
                else -> g.put("state", "none")
            }
        }
        return rows
    }

    private val cats = listOf("Action", "Adventure", "Racing", "Open World", "Survival", "Horror", "Simulation", "Strategy", "Sports", "Multiplayer")

    private fun home(): JSONObject {
        val counts = JSONObject()
        cats.forEach { counts.put(it, db.one("SELECT COUNT(*) c FROM catalog WHERE cats LIKE ?", "%,$it,%")?.getLong("c") ?: 0L) }
        counts.put("offline", db.one("SELECT COUNT(*) c FROM catalog WHERE offline=1")?.getLong("c") ?: 0L)
        val tally = HashMap<String, Int>()
        for (l in db.rows("SELECT cats FROM library").objs()) l.optString("cats").split(",").filter { it.isNotEmpty() }.forEach { tally[it] = (tally[it] ?: 0) + 1 }
        val top = tally.entries.sortedByDescending { it.value }.take(3).map { it.key }
        val rec = if (top.isEmpty()) JSONArray() else db.rows(
            "SELECT * FROM catalog WHERE (${top.joinToString(" OR ") { "cats LIKE ?" }}) AND pkg NOT IN (SELECT pkg FROM library) ORDER BY updated DESC LIMIT 15",
            *top.map { "%,$it," + "%" }.toTypedArray())
        val played = JSONArray()
        for (r in db.rows("SELECT * FROM library WHERE last_played>0 ORDER BY last_played DESC LIMIT 10").objs())
            if (Pm.code(act, r.getString("pkg")) >= 0) played.put(r.put("state", "installed").remove("files").let { r })
        return JSONObject()
            .put("featured", annotate(db.rows("SELECT * FROM catalog WHERE cover IS NOT NULL ORDER BY updated DESC LIMIT 8")))
            .put("added", annotate(db.rows("SELECT * FROM catalog ORDER BY added DESC LIMIT 15")))
            .put("updated", annotate(db.rows("SELECT * FROM catalog ORDER BY updated DESC LIMIT 15")))
            .put("recommended", annotate(rec)).put("played", played).put("counts", counts)
    }

    private fun query(a: JSONObject): JSONArray {
        val q = a.optString("q").trim(); val cat = a.optString("cat", "all"); val off = a.optInt("offset", 0)
        val where = StringBuilder("1=1"); val args = ArrayList<String>()
        if (q.isNotEmpty()) { where.append(" AND (name LIKE ? OR summary LIKE ? OR pkg LIKE ? OR author LIKE ?)"); repeat(4) { args.add("%$q%") } }
        when (cat) { "all" -> {}; "offline" -> where.append(" AND offline=1"); else -> { where.append(" AND cats LIKE ?"); args.add("%,$cat,%") } }
        val order = when (a.optString("sort")) { "updated" -> "updated DESC"; "name" -> "name COLLATE NOCASE"; else -> "added DESC" }
        return annotate(db.rows("SELECT * FROM catalog WHERE $where ORDER BY $order LIMIT 30 OFFSET $off", *args.toTypedArray()))
    }

    private fun catalogDownload(pkg: String): Long {
        val g = db.one("SELECT * FROM catalog WHERE pkg=?", pkg) ?: throw Err("UNKNOWN")
        val ex = db.one("SELECT id FROM downloads WHERE pkg=? AND state IN ('running','queued','paused','failed')", pkg)
        if (ex != null) { DownloadService.send(act, "start", ex.getLong("id")); return ex.getLong("id") }
        val dest = File(app.apkDir(), "${pkg}_${g.getLong("ver_code")}.apk")
        val meta = JSONObject().put("name", g.getString("name")).put("icon", g.optString("icon")).put("cover", g.optString("cover")).put("cats", g.optString("cats"))
        val id = db.insertDownload("fdroid", g.getString("name"), g.getString("apk_url"), dest.path, pkg, g.getString("sha256"), g.getLong("size"), meta.toString(), g.optString("icon").ifEmpty { null })
        DownloadService.send(act, "start", id)
        return id
    }

    private fun ghDownload(a: JSONObject): Long {
        val (o, r, _) = Gh.parse(a.getString("repo")) ?: throw Err("BADREPO")
        if (Gh.token(act) == null) throw Err("TOKEN")
        val aid = a.getLong("artifact")
        val url = "https://api.github.com/repos/$o/$r/actions/artifacts/$aid/zip"
        val ex = db.one("SELECT id FROM downloads WHERE url=? AND state IN ('running','queued','paused','failed')", url)
        if (ex != null) { DownloadService.send(act, "start", ex.getLong("id")); return ex.getLong("id") }
        val dest = File(app.apkDir(), "gh_${o}_${r}_$aid.zip")
        val id = db.insertDownload("github", "$o/$r · ${a.optString("name")}", url, dest.path, null, null, a.optLong("size"), JSONObject().toString(), null)
        DownloadService.send(act, "start", id)
        return id
    }

    private fun downloads(): JSONArray {
        val rows = db.rows("SELECT id,kind,title,pkg,bytes,total,state,error,icon,created FROM downloads ORDER BY id DESC LIMIT 40")
        rows.objs().forEach { r -> DownloadService.live[r.getLong("id")]?.let { r.put("bps", it[2]) } }
        return rows
    }

    private fun play(pkg: String, force: Boolean): JSONObject {
        if (Pm.code(act, pkg) < 0) throw Err("NOT_INSTALLED")
        val i = act.packageManager.getLaunchIntentForPackage(pkg) ?: throw Err("NOT_INSTALLED")
        if (!force && Perf.pre(pkg)) {
            val w = Perf.snapshot(act).getJSONArray("tips")
            if (w.length() > 0) return JSONObject().put("warnings", w)
        }
        db.exec("UPDATE library SET last_played=? WHERE pkg=?", System.currentTimeMillis(), pkg)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(i)
        return JSONObject().put("launched", true)
    }

    private fun perfGames(): JSONArray {
        val out = JSONArray()
        for (r in db.rows("SELECT pkg,name,icon FROM library").objs())
            if (Pm.code(act, r.getString("pkg")) >= 0 && out.objs().none { it.getString("pkg") == r.getString("pkg") })
                out.put(r.put("pre", Perf.pre(r.getString("pkg"))))
        return out
    }

    private fun shortcut(pkg: String): Boolean {
        val i = act.packageManager.getLaunchIntentForPackage(pkg) ?: throw Err("NOT_INSTALLED")
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(act)) throw Err("UNKNOWN")
        val label = act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(pkg, 0))
        val sc = ShortcutInfoCompat.Builder(act, "play_$pkg").setShortLabel(label).setIntent(i)
            .setIcon(IconCompat.createWithBitmap(act.packageManager.getApplicationIcon(pkg).toBitmap(128, 128))).build()
        return ShortcutManagerCompat.requestPinShortcut(act, sc, null)
    }
}
