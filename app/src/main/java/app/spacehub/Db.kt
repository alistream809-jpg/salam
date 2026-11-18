package app.spacehub

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objs(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

class Db(c: Context) : SQLiteOpenHelper(c, "spacehub.db", null, 1) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE catalog(pkg TEXT PRIMARY KEY,name TEXT,summary TEXT,author TEXT,cats TEXT,offline INTEGER,icon TEXT,cover TEXT,added INTEGER,updated INTEGER,ver_name TEXT,ver_code INTEGER,apk_url TEXT,sha256 TEXT,size INTEGER,min_sdk INTEGER)")
        d.execSQL("CREATE TABLE library(id INTEGER PRIMARY KEY AUTOINCREMENT,pkg TEXT,name TEXT,ver_name TEXT,ver_code INTEGER,size INTEGER,icon TEXT,cover TEXT,cats TEXT,source TEXT,files TEXT,pending INTEGER DEFAULT 0,added_at INTEGER,last_played INTEGER DEFAULT 0)")
        d.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT,title TEXT,url TEXT,dest TEXT,pkg TEXT,sha256 TEXT,bytes INTEGER DEFAULT 0,total INTEGER DEFAULT 0,state TEXT,error TEXT,meta TEXT,icon TEXT,created INTEGER)")
    }

    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}

    fun rows(sql: String, vararg a: String): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(sql, a).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in 0 until c.columnCount) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                        Cursor.FIELD_TYPE_NULL -> {}
                        else -> o.put(c.getColumnName(i), c.getString(i))
                    }
                }
                out.put(o)
            }
        }
        return out
    }

    fun one(sql: String, vararg a: String): JSONObject? = rows(sql, *a).optJSONObject(0)

    fun exec(sql: String, vararg a: Any?) { writableDatabase.execSQL(sql, arrayOf<Any?>(*a)) }

    fun markInterrupted() =
        exec("UPDATE downloads SET state='paused',error='INTERRUPTED' WHERE state IN ('running','queued')")

    fun insertDownload(kind: String, title: String, url: String, dest: String, pkg: String?, sha: String?, total: Long, meta: String, icon: String?): Long {
        val v = ContentValues().apply {
            put("kind", kind); put("title", title); put("url", url); put("dest", dest)
            put("pkg", pkg); put("sha256", sha); put("total", total); put("state", "queued")
            put("meta", meta); put("icon", icon); put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("downloads", null, v)
    }

    fun setState(id: Long, state: String, err: String?) = exec("UPDATE downloads SET state=?,error=? WHERE id=?", state, err, id)
    fun setProgress(id: Long, b: Long, t: Long) = exec("UPDATE downloads SET bytes=?,total=? WHERE id=?", b, t, id)

    fun replaceCatalog(items: List<Catalog.Game>) {
        val d = writableDatabase
        d.beginTransaction()
        try {
            d.delete("catalog", null, null)
            for (g in items) {
                d.insert("catalog", null, ContentValues().apply {
                    put("pkg", g.pkg); put("name", g.name); put("summary", g.summary); put("author", g.author)
                    put("cats", g.cats); put("offline", if (g.offline) 1 else 0)
                    put("icon", g.icon); put("cover", g.cover); put("added", g.added); put("updated", g.updated)
                    put("ver_name", g.verName); put("ver_code", g.verCode); put("apk_url", g.apkUrl)
                    put("sha256", g.sha); put("size", g.size); put("min_sdk", g.minSdk)
                })
            }
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }
}
