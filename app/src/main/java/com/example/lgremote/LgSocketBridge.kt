package com.example.lgremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Native WebSocket client for wss:// connections to LG TVs (port 3001).
 *
 * webOS serves that port with a self-signed certificate, which a WebView WebSocket
 * cannot accept. This bridge accepts that certificate, but ONLY for private
 * RFC1918 IPv4 literals; anything else is refused.
 */
class LgSocketBridge(private val context: Context, private val webView: WebView) {

    private val sockets = ConcurrentHashMap<Int, WebSocket>()

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val client: OkHttpClient = run {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { host, _ -> isPrivateIpv4(host) }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    @JavascriptInterface
    fun open(id: Int, url: String) {
        val host = try { URI(url).takeIf { it.scheme == "wss" }?.host } catch (e: Exception) { null }
        if (host == null || !isPrivateIpv4(host)) {
            emit(id, "error", "blocked: only local network addresses are allowed")
            emit(id, "close", 1006, "blocked")
            return
        }
        val ws = client.newWebSocket(Request.Builder().url(url.replaceFirst("wss://", "https://")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = emit(id, "open")
                override fun onMessage(webSocket: WebSocket, text: String) = emit(id, "message", text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(id); emit(id, "close", code, reason)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sockets.remove(id)
                    emit(id, "error", describe(t))
                    emit(id, "close", 1006, describe(t))
                }
            })
        sockets[id] = ws
    }

    @JavascriptInterface
    fun send(id: Int, text: String) { sockets[id]?.send(text) }

    @JavascriptInterface
    fun close(id: Int, code: Int, reason: String) {
        sockets[id]?.close(if (code in 1000..4999) code else 1000, reason.take(100))
    }

    /** True when the phone has a Wi-Fi connection (even one without internet). */
    @Suppress("DEPRECATION")
    @JavascriptInterface
    fun hasWifi(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    } catch (e: Exception) { true }

    private val discovering = AtomicBoolean(false)

    /**
     * Finds LG webOS TVs on the Wi-Fi with SSDP (UDP multicast), the same way LG's own
     * apps do. Results arrive via window.__lgFound(token, ip, name).
     */
    @JavascriptInterface
    fun discover(token: Int) {
        if (!discovering.compareAndSet(false, true)) return
        Thread {
            var lock: WifiManager.MulticastLock? = null
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                lock = wm.createMulticastLock("lgremote-ssdp").apply { setReferenceCounted(false); acquire() }
                val seen = HashSet<String>()
                DatagramSocket().use { sock ->
                    sock.soTimeout = 400
                    val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 3\r\n" +
                        "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n").toByteArray()
                    val group = InetAddress.getByName("239.255.255.250")
                    val end = SystemClock.elapsedRealtime() + 5000
                    var nextSend = 0L
                    val buf = ByteArray(2048)
                    while (SystemClock.elapsedRealtime() < end) {
                        if (SystemClock.elapsedRealtime() >= nextSend) {
                            sock.send(DatagramPacket(msg, msg.size, group, 1900))
                            nextSend = SystemClock.elapsedRealtime() + 1500
                        }
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            val ip = p.address.hostAddress ?: continue
                            val text = String(p.data, 0, p.length)
                            if (!isPrivateIpv4(ip) || !text.contains("webos", ignoreCase = true) || !seen.add(ip)) continue
                            val loc = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                            val name = friendlyName(loc)
                            webView.post {
                                webView.evaluateJavascript(
                                    "window.__lgFound&&window.__lgFound($token,${JSONObject.quote(ip)},${JSONObject.quote(name)})", null)
                            }
                        } catch (e: SocketTimeoutException) { /* keep listening */ }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("LGRemote", "SSDP discovery failed: $e")
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                discovering.set(false)
            }
        }.start()
    }

    private fun friendlyName(location: String?): String {
        if (location == null) return ""
        return try {
            val host = URL(location).host
            if (!isPrivateIpv4(host)) return ""
            val conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 1200; conn.readTimeout = 1200
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) { "" }
    }

    /**
     * Loads an app icon from the TV (http or self-signed https). Only images from private
     * LAN addresses are served; anything else gets a 404 so the page falls back to its art.
     */
    fun fetchImage(url: String): WebResourceResponse? {
        val host = try { URL(url).host } catch (e: Exception) { return null }
        if (!isPrivateIpv4(host)) return null
        return try {
            val c = client.newBuilder().readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
            val resp = c.newCall(Request.Builder().url(url).build()).execute()
            val type = resp.header("Content-Type")?.substringBefore(';')?.trim() ?: ""
            val body = resp.body
            if (!resp.isSuccessful || !type.startsWith("image/") || body == null) {
                resp.close(); notFound()
            } else {
                WebResourceResponse(type, null, body.byteStream())
            }
        } catch (e: Exception) { notFound() }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    fun closeAll() {
        sockets.values.forEach { it.cancel() }
        sockets.clear()
    }

    private fun describe(t: Throwable): String = when (t) {
        is SocketTimeoutException -> "timed out"
        is ConnectException -> "connection refused"
        is NoRouteToHostException -> "no route to TV"
        is SSLException -> "secure handshake failed"
        else -> t.message?.take(80) ?: t.javaClass.simpleName
    }

    private fun emit(id: Int, type: String, a: Any? = null, b: Any? = null) {
        fun lit(v: Any?): String = when (v) {
            null -> "null"
            is Number -> v.toString()
            else -> JSONObject.quote(v.toString())
        }
        val js = "window.__lgWsEvent&&window.__lgWsEvent($id,${JSONObject.quote(type)},${lit(a)},${lit(b)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    companion object {
        fun isPrivateIpv4(host: String): Boolean {
            val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host) ?: return false
            val p = m.groupValues.drop(1).map { it.toInt() }
            if (p.any { it > 255 }) return false
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
        }
    }
}
