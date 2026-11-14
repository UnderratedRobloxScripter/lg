package com.example.lgremote

import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.ConnectException
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
class LgSocketBridge(private val webView: WebView) {

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
