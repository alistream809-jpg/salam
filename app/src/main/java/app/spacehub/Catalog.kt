package app.spacehub

import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Real game catalog: F-Droid's official repository index (index-v2.json), filtered to the "Games" category.
 * F-Droid publishes open-source apps with SHA-256 checksums, which SpaceHub verifies after download.
 */
object Catalog {
    const val REPO = "https://f-droid.org/repo"
    @Volatile var running = false

    class Game(
        val pkg: String, val name: String, val summary: String, val author: String, val cats: String,
        val offline: Boolean, val icon: String?, val cover: String?, val added: Long, val updated: Long,
        val verName: String, val verCode: Long, val apkUrl: String, val sha: String, val size: Long, val minSdk: Int
    )

    private class Meta {
        var name: String? = null; var summary: String? = null; var author: String? = null
        var icon: String? = null; var cover: String? = null
        var added = 0L; var updated = 0L; var isGame = false
    }

    private class Ver {
        var file: String? = null; var sha: String? = null; var size = 0L
        var name = ""; var code = 0L; var minSdk = 0; var maxSdk = 0
        val abis = ArrayList<String>(); var internet = false
        fun compatible(deviceAbis: Set<String>, sdk: Int) =
            minSdk <= sdk && (maxSdk == 0 || sdk <= maxSdk) && (abis.isEmpty() || abis.any { it in deviceAbis })
    }

    // Keyword classification of the real name+summary text. F-Droid itself only has a single "Games" category.
    private val kw: Map<String, Regex> = mapOf(
        "Action" to "action|shooter|shoot|fight|fighting|arcade|platformer|roguelike|beat em up",
        "Adventure" to "adventure|quest|rpg|role playing|story|dungeon",
        "Racing" to "racing|race|kart|drift|rally",
        "Open World" to "open world|sandbox|voxel|block building",
        "Survival" to "survival|survive",
        "Horror" to "horror|zombie|zombies",
        "Simulation" to "simulation|simulator|tycoon|management|city builder",
        "Strategy" to "strategy|tower defense|turn based|chess|tactics|4x|board game",
        "Sports" to "sport|sports|soccer|football|basketball|golf|tennis|cricket|ski|skiing",
        "Multiplayer" to "multiplayer|online|network|lan|co op|coop|p2p"
    ).mapValues { Regex("\\b(${it.value})\\b") }

    fun classify(text: String): String {
        val t = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
        val hits = kw.filter { it.value.containsMatchIn(t) }.keys
        return if (hits.isEmpty()) "," else hits.joinToString(",", ",", ",")
    }

    /** Returns null on success or an error code. */
    fun refresh(onCount: (Int) -> Unit): String? {
        if (running) return null
        running = true
        try {
            val c = URL("$REPO/index-v2.json").openConnection() as HttpsURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "SpaceHub/1.0")
            if (c.responseCode != 200) return "NETWORK"
            val abis = Build.SUPPORTED_ABIS.toSet()
            val list = c.inputStream.use { ins ->
                JsonReader(InputStreamReader(ins, "UTF-8").buffered(64 * 1024)).use { r -> parse(r, abis, Build.VERSION.SDK_INT, onCount) }
            }
            if (list.isEmpty()) return "PARSE"
            SpaceApp.I.db.replaceCatalog(list)
            SpaceApp.I.prefs.edit().putLong("catalog_at", System.currentTimeMillis()).apply()
            return null
        } catch (e: IOException) { return "NETWORK" } catch (e: Exception) { return "PARSE" } finally { running = false }
    }

    private fun parse(r: JsonReader, abis: Set<String>, sdk: Int, onCount: (Int) -> Unit): List<Game> {
        val out = ArrayList<Game>()
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "packages") {
                r.beginObject()
                while (r.hasNext()) {
                    val pkg = r.nextName()
                    parsePkg(r, pkg, abis, sdk)?.let { out.add(it); if (out.size % 25 == 0) onCount(out.size) }
                }
                r.endObject()
            } else r.skipValue()
        }
        r.endObject()
        onCount(out.size)
        return out
    }

    private fun parsePkg(r: JsonReader, pkg: String, abis: Set<String>, sdk: Int): Game? {
        var meta: Meta? = null
        var best: Ver? = null
        var skipVersions = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "metadata" -> { val m = parseMeta(r); meta = m; if (!m.isGame) skipVersions = true }
                "versions" -> if (skipVersions) r.skipValue() else best = parseVersions(r, abis, sdk)
                else -> r.skipValue()
            }
        }
        r.endObject()
        val m = meta ?: return null
        val v = best ?: return null
        if (!m.isGame) return null
        val name = m.name ?: pkg
        return Game(
            pkg, name, m.summary ?: "", m.author ?: "", classify("$name ${m.summary ?: ""}"), !v.internet,
            m.icon?.let { REPO + it }, m.cover?.let { REPO + it }, m.added, m.updated,
            v.name, v.code, REPO + v.file, v.sha ?: "", v.size, v.minSdk
        )
    }

    private fun parseMeta(r: JsonReader): Meta {
        val m = Meta()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "name" -> m.name = loc(r)
                "summary" -> m.summary = loc(r)
                "authorName" -> m.author = strOr(r)
                "categories" -> {
                    r.beginArray()
                    while (r.hasNext()) { if (r.nextString().equals("Games", true)) m.isGame = true }
                    r.endArray()
                }
                "icon" -> m.icon = locFile(r)
                "featureGraphic" -> m.cover = locFile(r)
                "added" -> m.added = numOr(r)
                "lastUpdated" -> m.updated = numOr(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    private fun parseVersions(r: JsonReader, abis: Set<String>, sdk: Int): Ver? {
        var best: Ver? = null
        r.beginObject()
        while (r.hasNext()) {
            r.nextName()
            val v = parseVer(r)
            val b = best
            if (v != null && v.compatible(abis, sdk) && (b == null || v.code > b.code)) best = v
        }
        r.endObject()
        return best
    }

    private fun parseVer(r: JsonReader): Ver? {
        val v = Ver()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "file" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "name" -> v.file = strOr(r)
                            "sha256" -> v.sha = strOr(r)
                            "size" -> v.size = numOr(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                "manifest" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "versionName" -> v.name = strOr(r)
                            "versionCode" -> v.code = numOr(r)
                            "maxSdkVersion" -> v.maxSdk = numOr(r).toInt()
                            "usesSdk" -> {
                                r.beginObject()
                                while (r.hasNext()) { if (r.nextName() == "minSdkVersion") v.minSdk = numOr(r).toInt() else r.skipValue() }
                                r.endObject()
                            }
                            "nativecode" -> { r.beginArray(); while (r.hasNext()) v.abis.add(strOr(r)); r.endArray() }
                            "usesPermission" -> {
                                r.beginArray()
                                while (r.hasNext()) {
                                    var n = ""
                                    r.beginObject()
                                    while (r.hasNext()) { if (r.nextName() == "name") n = strOr(r) else r.skipValue() }
                                    r.endObject()
                                    if (n == "android.permission.INTERNET") v.internet = true
                                }
                                r.endArray()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return if (!v.file.isNullOrEmpty() && !v.sha.isNullOrEmpty()) v else null
    }

    private fun strOr(r: JsonReader): String = if (r.peek() == JsonToken.NULL) { r.nextNull(); "" } else r.nextString()
    private fun numOr(r: JsonReader): Long = if (r.peek() == JsonToken.NULL) { r.nextNull(); 0L } else r.nextDouble().toLong()

    /** Localized string map: {"en-US": "..."} -> prefers English, else first available. */
    private fun loc(r: JsonReader): String? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        var first: String? = null; var en: String? = null
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            if (r.peek() == JsonToken.STRING) {
                val s = r.nextString()
                if (first == null) first = s
                if (en == null && k.startsWith("en")) en = s
            } else r.skipValue()
        }
        r.endObject()
        return en ?: first
    }

    /** Localized file map: {"en-US": {"name": "/pkg/en-US/icon.png"}} */
    private fun locFile(r: JsonReader): String? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        var first: String? = null; var en: String? = null
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            if (r.peek() == JsonToken.BEGIN_OBJECT) {
                var n: String? = null
                r.beginObject()
                while (r.hasNext()) { if (r.nextName() == "name") n = strOr(r) else r.skipValue() }
                r.endObject()
                if (!n.isNullOrEmpty()) {
                    if (first == null) first = n
                    if (en == null && k.startsWith("en")) en = n
                }
            } else r.skipValue()
        }
        r.endObject()
        return en ?: first
    }
}
