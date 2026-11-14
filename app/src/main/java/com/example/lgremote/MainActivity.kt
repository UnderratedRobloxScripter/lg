package com.example.lgremote

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import java.io.ByteArrayInputStream

/**
 * Hosts the unchanged remote UI (assets/index.html) in a WebView under a stable
 * https origin, so localStorage (saved TVs and pairing keys) persists.
 *
 *  - MIXED_CONTENT_ALWAYS_ALLOW lets the https page open ws://TV_IP:3000.
 *  - wss://TV_IP:3001 (self-signed) is routed through LgSocketBridge via lg_ws_shim.js.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var bridge: LgSocketBridge? = null

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        bridge = LgSocketBridge(webView).also { webView.addJavascriptInterface(it, "LGBridge") }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("LGRemote", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.scheme == "https" && u.host == APP_HOST && (u.path == "/" || u.path == "/index.html")) {
                    return serveIndex()
                }
                return null // fonts etc. load normally
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == APP_HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }
        }

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(APP_URL)
    }

    /** Serves assets/index.html with the WebSocket shim inserted before any page script. */
    private fun serveIndex(): WebResourceResponse {
        val html = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val shim = assets.open("lg_ws_shim.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val patched = html.replaceFirst("<head>", "<head><script>$shim</script>")
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        bridge?.closeAll()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = "app.lgremote.local"
        private const val APP_URL = "https://$APP_HOST/index.html"
    }
}
