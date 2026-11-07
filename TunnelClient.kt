package com.airfree.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps a WebSocket open to the relay. Each request the relay forwards is replayed
 * against the local web server (127.0.0.1) and the answer is streamed back.
 *
 * Messages:  text = JSON {t: req|end|abort|res|err, id, ...}
 *            binary = 4-byte request id followed by body bytes
 */
class TunnelClient(
    private val relayBase: String,
    private val deviceId: String,
    private val localPort: Int,
    private val tmpDir: File
) {
    private class Req(
        val id: Int,
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val ip: String,
        val hasBody: Boolean
    ) {
        var bodyFile: File? = null
        var bodyOut: FileOutputStream? = null
        @Volatile var aborted = false
        @Volatile var conn: HttpURLConnection? = null
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val pool = Executors.newCachedThreadPool()
    private val reqs = ConcurrentHashMap<Int, Req>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    private var gen = 0

    fun start() = connect()

    fun stop() {
        stopped = true
        Shared.relayConnected = false
        main.removeCallbacksAndMessages(null)
        for (r in reqs.values) abort(r)
        ws?.close(1000, null)
        ws = null
        pool.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    private fun connect() {
        if (stopped) return
        val myGen = synchronized(this) { ++gen }
        val url = relayBase.replaceFirst("http", "ws") + "/tunnel?id=" + deviceId
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Shared.relayConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleText(text)
                } catch (_: Exception) {
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleBinary(bytes)
                } catch (_: Exception) {
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lost(myGen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lost(myGen)
            }
        })
    }

    private fun lost(g: Int) {
        synchronized(this) {
            if (g != gen) return
            gen++
        }
        Shared.relayConnected = false
        for (r in reqs.values) abort(r)
        if (!stopped) main.postDelayed({ connect() }, 4000)
    }

    private fun abort(r: Req) {
        r.aborted = true
        try {
            r.conn?.disconnect()
        } catch (_: Exception) {
        }
        try {
            r.bodyOut?.close()
        } catch (_: Exception) {
        }
        r.bodyFile?.delete()
        reqs.remove(r.id)
    }

    private fun handleText(text: String) {
        val j = JSONObject(text)
        val id = j.getInt("id")
        when (j.getString("t")) {
            "req" -> {
                val h = HashMap<String, String>()
                val hj = j.optJSONObject("headers")
                if (hj != null) for (k in hj.keys()) h[k.lowercase()] = hj.getString(k)
                val r = Req(
                    id, j.getString("method"), j.getString("path"), h,
                    j.optString("ip", ""), j.optBoolean("hasBody")
                )
                reqs[id] = r
                if (r.hasBody) {
                    val f = File.createTempFile("tun", ".bin", tmpDir)
                    r.bodyFile = f
                    r.bodyOut = FileOutputStream(f)
                } else {
                    pool.execute { run(r) }
                }
            }
            "end" -> reqs[id]?.let { r ->
                r.bodyOut?.close()
                pool.execute { run(r) }
            }
            "abort" -> reqs[id]?.let { abort(it) }
        }
    }

    private fun handleBinary(b: ByteString) {
        val arr = b.toByteArray()
        if (arr.size < 4) return
        val id = ByteBuffer.wrap(arr, 0, 4).int
        reqs[id]?.bodyOut?.write(arr, 4, arr.size - 4)
    }

    private fun run(r: Req) {
        val sock = ws ?: return
        try {
            val conn = URL("http://127.0.0.1:$localPort${r.path}").openConnection() as HttpURLConnection
            r.conn = conn
            conn.requestMethod = r.method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = 120000
            r.headers["content-type"]?.let { conn.setRequestProperty("Content-Type", it) }
            r.headers["authorization"]?.let { conn.setRequestProperty("Authorization", it) }
            if (r.ip.isNotEmpty()) conn.setRequestProperty("X-Real-Ip", r.ip)

            val bf = r.bodyFile
            if (bf != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bf.length())
                FileInputStream(bf).use { input -> conn.outputStream.use { out -> input.copyTo(out) } }
            }

            val code = conn.responseCode
            val h = JSONObject()
            for (name in listOf("Content-Type", "Content-Length", "Content-Disposition", "Cache-Control")) {
                conn.getHeaderField(name)?.let { h.put(name.lowercase(), it) }
            }
            sock.send(
                JSONObject().put("t", "res").put("id", r.id).put("status", code).put("headers", h).toString()
            )

            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            stream?.use { s ->
                val buf = ByteArray(128 * 1024)
                while (!r.aborted) {
                    val n = s.read(buf)
                    if (n < 0) break
                    while (sock.queueSize() > 2_000_000 && !r.aborted && Shared.relayConnected) {
                        Thread.sleep(20)
                    }
                    if (r.aborted || !Shared.relayConnected) break
                    val frame = ByteBuffer.allocate(4 + n).putInt(r.id).put(buf, 0, n).array()
                    if (!sock.send(frame.toByteString())) break
                }
            }
            if (!r.aborted) sock.send(JSONObject().put("t", "end").put("id", r.id).toString())
        } catch (e: Exception) {
            if (!r.aborted) {
                sock.send(
                    JSONObject().put("t", "err").put("id", r.id).put("msg", e.message ?: "error").toString()
                )
            }
        } finally {
            r.bodyFile?.delete()
            reqs.remove(r.id)
        }
    }
}
