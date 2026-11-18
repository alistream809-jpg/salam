package app.spacehub

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    lateinit var web: WebView
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        SpaceApp.I.emit("perm", JSONObject().put("notif", it))
    }

    fun askNotif() { if (Build.VERSION.SDK_INT >= 33) runOnUiThread { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val app = SpaceApp.I
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(Color.parseColor("#05070F"))
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host == "appassets.androidplatform.net") return false
                if (r.url.scheme == "https") startActivity(Intent(Intent.ACTION_VIEW, r.url))
                return true
            }
        }
        web.addJavascriptInterface(Bridge(this, web), "Native")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        app.sink = { n, o -> web.evaluateJavascript("window.__event('$n',${JSONObject.quote(o.toString())})", null) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__back()") { r ->
                    if (r != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    override fun onResume() { super.onResume(); SpaceApp.I.emit("resume", JSONObject()) }
    override fun onDestroy() { SpaceApp.I.sink = null; web.removeJavascriptInterface("Native"); super.onDestroy() }
}
