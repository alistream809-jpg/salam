package app.spacehub

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

object Pm {
    /** Installed versionCode of [pkg], or -1 if it is not installed. */
    @Suppress("DEPRECATION")
    fun code(ctx: Context, pkg: String): Long = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) { -1L }
}

object Validator {
    class Fail(val code: String) : Exception(code)
    class Info(val pkg: String, val label: String, val verName: String, val verCode: Long, val minSdk: Int, val file: File, val pi: PackageInfo)
    class ApkSet(val base: Info, val files: List<File>)

    /** Reads the real package info from the APK file. Null = not a valid base APK. */
    @Suppress("DEPRECATION")
    fun inspect(ctx: Context, f: File): Info? {
        if (!f.isFile || f.length() < 4) return null
        val magic = ByteArray(2)
        f.inputStream().use { it.read(magic) }
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) return null
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val pi = ctx.packageManager.getPackageArchiveInfo(f.path, flags) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = f.path; ai.publicSourceDir = f.path
        val label = ai.loadLabel(ctx.packageManager).toString()
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return Info(pi.packageName, label, pi.versionName ?: "", code, ai.minSdkVersion, f, pi)
    }

    /**
     * Validates a set of APK files: one base APK (parsable) plus optional split APKs (not parsable on their own).
     * If several buildable base APKs are present, "unsigned" ones are ranked last.
     */
    fun analyze(ctx: Context, files: List<File>): ApkSet {
        val apks = files.filter { it.name.endsWith(".apk", true) }
        val parsed = apks.mapNotNull { inspect(ctx, it) }
        if (parsed.isEmpty()) throw Fail("INVALID")
        val base = parsed.sortedWith(compareBy({ it.file.name.contains("unsigned", true) }, { it.file.name })).first()
        if (base.minSdk > Build.VERSION.SDK_INT) throw Fail("SDK")
        val splits = apks.filter { f -> parsed.none { it.file == f } }
        return ApkSet(base, listOf(base.file) + splits)
    }

    fun iconUri(ctx: Context, i: Info): String? = try {
        val bmp = i.pi.applicationInfo!!.loadIcon(ctx.packageManager).toBitmap(128, 128)
        val bo = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, bo)
        "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) { null }
}

object Zip {
    /** Extracts only *.apk entries, flattening paths (no zip-slip) with entry-count and size caps. */
    fun extractApks(zip: File, outDir: File): List<File> {
        outDir.deleteRecursively(); outDir.mkdirs()
        val out = ArrayList<File>()
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(".apk", true)) {
                    if (out.size >= 24) throw Validator.Fail("INVALID")
                    val safe = File(e.name).name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val f = File(outDir, "${out.size}_$safe")
                    f.outputStream().use { o ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 8L * 1024 * 1024 * 1024) throw Validator.Fail("STORAGE")
                            o.write(buf, 0, n)
                        }
                    }
                    out.add(f)
                }
                e = z.nextEntry
            }
        }
        if (out.isEmpty()) throw Validator.Fail("NO_APK")
        return out
    }
}
