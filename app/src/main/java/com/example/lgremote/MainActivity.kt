package com.example.lgremote

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
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

        bridge = LgSocketBridge(this, webView).also { webView.addJavascriptInterface(it, "LGBridge") }

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
                val h = u.host ?: ""
                if (request.method == "GET" && (u.scheme == "http" || u.scheme == "https") && LgSocketBridge.isPrivateIpv4(h)) {
                    return bridge?.fetchImage(u.toString()) // TV app icons
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

    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Pin this app's traffic to Wi-Fi. Without this Android may send it over mobile data when the
     * Wi-Fi has no internet, and a TV on the LAN becomes unreachable.
     */
    private fun bindToWifi() {
        if (wifiCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { cm.bindProcessToNetwork(network) }
                override fun onLost(network: Network) { cm.bindProcessToNetwork(null) }
            }
            cm.requestNetwork(req, cb)
            wifiCallback = cb
        } catch (e: Exception) {
            android.util.Log.w("LGRemote", "Could not bind to Wi-Fi: $e")
        }
    }

    /** Keep Wi-Fi out of power-save while the remote is on screen, and reconnect if the socket died. */
    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        bindToWifi()
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "LGRemote").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        webView.evaluateJavascript("window.__lgResume&&window.__lgResume()", null)
    }

    override fun onPause() {
        super.onPause()
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
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
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            wifiCallback?.let { cm.unregisterNetworkCallback(it) }
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {}
        wifiCallback = null
        bridge?.closeAll()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = "app.lgremote.local"
        private const val APP_URL = "https://$APP_HOST/index.html"
    }
}
