package app.spacehub

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Actions client used ONLY to find and download APK artifacts. Not a game catalog. */
object Gh {
    class GhErr(val code: String) : Exception(code)

    private fun prefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(ctx, "gh_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    fun token(ctx: Context): String? = try { prefs(ctx).getString("token", null)?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
    fun setToken(ctx: Context, t: String?) {
        prefs(ctx).edit().apply { if (t.isNullOrBlank()) remove("token") else putString("token", t.trim()) }.apply()
    }

    fun authHeaders(ctx: Context): Map<String, String> {
        val t = token(ctx) ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $t", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    }

    /** Accepts owner/repo, a repo URL, or a URL to a .github/workflows/*.yml file. Returns (owner, repo, workflowFile?). */
    fun parse(input: String): Triple<String, String, String?>? {
        val s = input.trim()
        var p = s.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").removePrefix("www.")
        if (s.contains("://") || p.startsWith("github.com/")) {
            if (!p.startsWith("github.com/")) return null
            p = p.removePrefix("github.com/")
        }
        val seg = p.split('/').filter { it.isNotEmpty() }
        if (seg.size < 2) return null
        val ok = Regex("^[A-Za-z0-9_.-]+$")
        val owner = seg[0]; val repo = seg[1].removeSuffix(".git")
        if (!ok.matches(owner) || !ok.matches(repo)) return null
        val wf = seg.last().takeIf { (it.endsWith(".yml") || it.endsWith(".yaml")) && seg.contains("workflows") }
        return Triple(owner, repo, wf)
    }

    private fun get(ctx: Context, path: String): JSONObject {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "SpaceHub/1.0")
        token(ctx)?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        try {
            return when (c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                401 -> throw GhErr("AUTH")
                403 -> throw GhErr(if (c.getHeaderField("X-RateLimit-Remaining") == "0") "RATE" else "AUTH")
                404 -> throw GhErr("NOTFOUND")
                429 -> throw GhErr("RATE")
                else -> throw GhErr("NETWORK")
            }
        } catch (e: IOException) { throw GhErr("NETWORK") } finally { c.disconnect() }
    }

    fun workflows(ctx: Context, o: String, r: String): JSONArray {
        val out = JSONArray()
        val arr = get(ctx, "/repos/$o/$r/actions/workflows?per_page=100").getJSONArray("workflows")
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            val path = w.getString("path")
            if (path.endsWith(".yml") || path.endsWith(".yaml"))
                out.put(JSONObject().put("id", w.getLong("id")).put("name", w.getString("name")).put("file", path.substringAfterLast('/')).put("state", w.optString("state")))
        }
        return out
    }

    fun runs(ctx: Context, o: String, r: String, wf: String?): JSONArray {
        val p = if (wf.isNullOrEmpty()) "/repos/$o/$r/actions/runs?per_page=20" else "/repos/$o/$r/actions/workflows/$wf/runs?per_page=20"
        val arr = get(ctx, p).getJSONArray("workflow_runs")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            fun s(k: String) = if (x.isNull(k)) "" else x.getString(k)
            out.put(JSONObject().put("id", x.getLong("id")).put("number", x.optInt("run_number")).put("title", s("display_title").ifEmpty { s("name") })
                .put("branch", s("head_branch")).put("status", s("status")).put("conclusion", s("conclusion"))
                .put("created", s("created_at")).put("url", s("html_url")))
        }
        return out
    }

    fun artifacts(ctx: Context, o: String, r: String, run: Long): JSONArray {
        val arr = get(ctx, "/repos/$o/$r/actions/runs/$run/artifacts?per_page=30").getJSONArray("artifacts")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            out.put(JSONObject().put("id", x.getLong("id")).put("name", x.getString("name")).put("size", x.optLong("size_in_bytes"))
                .put("expired", x.optBoolean("expired")))
        }
        return out
    }
}
