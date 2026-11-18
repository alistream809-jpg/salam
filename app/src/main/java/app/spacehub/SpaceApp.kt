package app.spacehub

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

class SpaceApp : Application() {
    lateinit var db: Db
    lateinit var prefs: SharedPreferences
    @Volatile var sink: ((String, JSONObject) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        I = this
        db = Db(this)
        prefs = getSharedPreferences("sh", MODE_PRIVATE)
        // Anything that was mid-download when the process died becomes "paused" and can be resumed.
        db.markInterrupted()
    }

    /** Sends an event to the WebView UI (if it is currently attached). */
    fun emit(name: String, o: JSONObject) { main.post { sink?.invoke(name, o) } }

    /** Native strings (notifications) are pushed from the JS translation table. */
    fun str(key: String, def: String): String = try {
        JSONObject(prefs.getString("native_strings", "{}") ?: "{}").optString(key, def)
    } catch (e: Exception) { def }

    fun apkDir(): File = (getExternalFilesDir("apks") ?: File(filesDir, "apks")).also { it.mkdirs() }

    companion object { lateinit var I: SpaceApp }
}
