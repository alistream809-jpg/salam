package app.spacehub

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only values the Android SDK actually reports. Nothing here is estimated. */
object Perf {
    fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject()
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        o.put("ram", JSONObject().put("total", mi.totalMem).put("avail", mi.availMem).put("low", mi.lowMemory))
        val sf = StatFs(Environment.getDataDirectory().path)
        o.put("storage", JSONObject().put("total", sf.totalBytes).put("avail", sf.availableBytes))
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        o.put("battery", JSONObject().put("pct", if (lvl >= 0) lvl * 100 / scale else -1)
            .put("tempC", if (temp >= 0) temp / 10.0 else JSONObject.NULL).put("charging", charging))
        val pm = ctx.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
        o.put("powerSave", pm.isPowerSaveMode).put("thermal", thermal)
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = JSONArray()
        for (i in 0 until cores) freqs.put(try { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000 } catch (e: Exception) { JSONObject.NULL })
        o.put("cpu", JSONObject().put("cores", cores).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("freqMhz", freqs))
        o.put("hz", (ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f).toDouble())
        o.put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)

        val tips = JSONArray()
        if (mi.lowMemory || mi.availMem * 100 / mi.totalMem < 15) tips.put("RAM")
        if (lvl in 0..14 && !charging) tips.put("BATTERY")
        if (pm.isPowerSaveMode) tips.put("POWERSAVE")
        if (thermal >= 2 || temp >= 430) tips.put("HOT")
        return o.put("tips", tips)
    }

    fun pre(pkg: String): Boolean {
        val p = SpaceApp.I.prefs
        return p.getBoolean("pre_$pkg", p.getBoolean("pre_default", true))
    }
}
