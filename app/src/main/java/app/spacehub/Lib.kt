package app.spacehub

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Lib {
    fun add(pkg: String, name: String, verName: String, verCode: Long, size: Long, icon: String?, cover: String?,
            cats: String?, source: String, files: List<File>): Long {
        val db = SpaceApp.I.db
        db.exec("DELETE FROM library WHERE pkg=? AND ver_code=?", pkg, verCode)
        val v = ContentValues().apply {
            put("pkg", pkg); put("name", name); put("ver_name", verName); put("ver_code", verCode); put("size", size)
            put("icon", icon); put("cover", cover); put("cats", cats); put("source", source)
            put("files", files.joinToString("|") { it.path }); put("added_at", System.currentTimeMillis())
        }
        return db.writableDatabase.insert("library", null, v)
    }

    /** Library grouped into installed / downloaded / pending, computed from the real PackageManager state. */
    fun list(ctx: Context): JSONObject {
        val db = SpaceApp.I.db
        val inst = JSONArray(); val dl = JSONArray(); val pend = JSONArray()
        for (o in db.rows("SELECT * FROM library ORDER BY added_at DESC").objs()) {
            val files = o.getString("files").split("|").map { File(it) }
            val have = files.all { it.exists() }
            val ic = Pm.code(ctx, o.getString("pkg"))
            if (!have && ic < 0) { db.exec("DELETE FROM library WHERE id=?", o.getLong("id")); continue }
            if (ic >= o.getLong("ver_code") && o.optLong("pending") == 1L) {
                db.exec("UPDATE library SET pending=0 WHERE id=?", o.getLong("id")); o.put("pending", 0)
            }
            o.remove("files")
            o.put("installedCode", ic); o.put("fileMissing", !have)
            if (have) o.put("size", files.sumOf { it.length() })
            when {
                ic >= o.getLong("ver_code") -> inst.put(o)
                o.optLong("pending") == 1L -> pend.put(o)
                else -> dl.put(o)
            }
        }
        return JSONObject().put("installed", inst).put("downloaded", dl).put("pending", pend)
    }

    fun remove(id: Long) {
        val db = SpaceApp.I.db
        val row = db.one("SELECT files FROM library WHERE id=?", id.toString()) ?: return
        row.getString("files").split("|").map { File(it) }.forEach {
            it.delete()
            val p = it.parentFile
            if (p != null && p.name.startsWith("gh_") && p.list()?.isEmpty() == true) p.delete()
        }
        db.exec("DELETE FROM library WHERE id=?", id)
    }
}
