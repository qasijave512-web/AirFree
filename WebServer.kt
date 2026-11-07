package com.airfree.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Telephony
import android.telephony.SmsManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

/** The phone-side HTTP server. Serves the dashboard and a small JSON API (all behind a PIN login). */
class WebServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    private class St(val code: Int, val text: String) : Response.IStatus {
        override fun getDescription() = "$code $text"
        override fun getRequestStatus() = code
    }

    private val tokens: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val fails = HashMap<String, Pair<Int, Long>>()
    private val rnd = SecureRandom()

    private val root: File get() = Environment.getExternalStorageDirectory()

    override fun serve(session: IHTTPSession): Response =
        try {
            route(session)
        } catch (e: Exception) {
            err(500, e.message ?: "Server error")
        }

    // ---------- helpers ----------

    private fun err(code: Int, msg: String): Response =
        newFixedLengthResponse(St(code, "Error"), "application/json", JSONObject().put("error", msg).toString())

    private fun ok(o: Any): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    private fun bodyJson(s: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        s.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private fun resolve(rel: String?): File? {
        val base = root.canonicalFile
        val f = File(base, rel ?: "").canonicalFile
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }

    private fun relPath(f: File): String =
        f.path.removePrefix(root.canonicalFile.path).trimStart(File.separatorChar)

    private fun has(permission: String) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---------- routing ----------

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri
        val m = s.method

        if (m == Method.GET && (uri == "/" || uri == "/index.html")) {
            val html = ctx.assets.open("web/index.html").use { String(it.readBytes(), Charsets.UTF_8) }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        if (uri == "/api/login" && m == Method.POST) return login(s)
        if (!authorized(s)) return err(401, "Please sign in")

        return when {
            uri == "/api/info" && m == Method.GET -> info()
            uri == "/api/files" && m == Method.GET -> listFiles(s)
            uri == "/api/download" && m == Method.GET -> download(s)
            uri == "/api/upload" && m == Method.POST -> upload(s)
            uri == "/api/file" && m == Method.DELETE -> deleteFile(s)
            uri == "/api/notifications" && m == Method.GET -> notifications()
            uri == "/api/sms" && m == Method.GET -> smsList()
            uri == "/api/sms/send" && m == Method.POST -> smsSend(s)
            uri == "/api/clipboard" && m == Method.GET -> ok(JSONObject().put("text", Shared.sharedText))
            uri == "/api/clipboard" && m == Method.POST -> setClipboard(s)
            uri == "/api/screen.jpg" && m == Method.GET -> screen()
            uri == "/api/tap" && m == Method.POST -> gesture(s, tap = true)
            uri == "/api/swipe" && m == Method.POST -> gesture(s, tap = false)
            uri == "/api/key" && m == Method.POST -> key(s)
            else -> err(404, "Not found")
        }
    }

    // ---------- auth ----------

    private fun authorized(s: IHTTPSession): Boolean {
        val header = s.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val t = header ?: s.parms["token"]
        return t != null && tokens.contains(t)
    }

    private fun login(s: IHTTPSession): Response {
        val remote = s.remoteIpAddress ?: "?"
        val viaTunnel = remote == "127.0.0.1" || remote == "::1" || remote == "0:0:0:0:0:0:0:1"
        val ip = if (viaTunnel) (s.headers["x-real-ip"] ?: remote) else remote
        val now = System.currentTimeMillis()
        synchronized(fails) {
            val f = fails[ip]
            if (f != null && f.first >= 5 && now - f.second < 30_000) {
                return err(429, "Too many attempts. Wait 30 seconds.")
            }
        }
        val pin = bodyJson(s).optString("pin")
        val real = Shared.pin(ctx)
        if (MessageDigest.isEqual(pin.toByteArray(), real.toByteArray())) {
            synchronized(fails) { fails.remove(ip) }
            val b = ByteArray(24)
            rnd.nextBytes(b)
            val token = b.joinToString("") { "%02x".format(it) }
            tokens.add(token)
            return ok(JSONObject().put("token", token))
        }
        synchronized(fails) {
            val f = fails[ip]
            fails[ip] = Pair((f?.first ?: 0) + 1, now)
        }
        return err(401, "Wrong PIN")
    }

    // ---------- device info ----------

    private fun info(): Response {
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val sf = StatFs(Environment.getExternalStorageDirectory().path)
        return ok(
            JSONObject()
                .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("battery", if (level >= 0) level * 100 / scale else -1)
                .put("charging", charging)
                .put("storageFree", sf.availableBytes)
                .put("storageTotal", sf.totalBytes)
                .put("screenSharing", Shared.frame != null)
                .put("remoteControl", ControlService.instance != null)
        )
    }

    // ---------- files ----------

    private fun listFiles(s: IHTTPSession): Response {
        val dir = resolve(s.parms["path"]) ?: return err(403, "Bad path")
        if (!dir.isDirectory) return err(404, "Not a folder")
        val list = dir.listFiles()
            ?: return err(403, "No access. Allow \"all files access\" in the AirFree app on the phone.")
        val items = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val arr = JSONArray()
        for (f in items) {
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("dir", f.isDirectory)
                    .put("size", f.length())
                    .put("time", f.lastModified())
            )
        }
        return ok(JSONObject().put("path", relPath(dir)).put("items", arr))
    }

    private fun download(s: IHTTPSession): Response {
        val f = resolve(s.parms["path"]) ?: return err(403, "Bad path")
        if (!f.isFile) return err(404, "File not found")
        val r = newFixedLengthResponse(
            Response.Status.OK, "application/octet-stream", FileInputStream(f), f.length()
        )
        val encoded = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
        r.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encoded")
        return r
    }

    private fun upload(s: IHTTPSession): Response {
        val dir = resolve(s.parms["path"]) ?: return err(403, "Bad path")
        if (!dir.isDirectory) return err(404, "Not a folder")
        val files = HashMap<String, String>()
        s.parseBody(files)
        val tmp = files["file"] ?: return err(400, "No file received")
        val name = File(s.parms["file"] ?: "upload").name.ifBlank { "upload" }
        val stem = if (name.contains('.')) name.substringBeforeLast('.') else name
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var dest = File(dir, name)
        var i = 1
        while (dest.exists()) {
            dest = File(dir, "$stem ($i)$ext")
            i++
        }
        File(tmp).copyTo(dest)
        return ok(JSONObject().put("ok", true).put("name", dest.name))
    }

    private fun deleteFile(s: IHTTPSession): Response {
        val f = resolve(s.parms["path"]) ?: return err(403, "Bad path")
        if (f.path == root.canonicalFile.path) return err(403, "Cannot delete the storage root")
        if (!f.exists()) return err(404, "Not found")
        val done = if (f.isDirectory) f.deleteRecursively() else f.delete()
        return if (done) ok(JSONObject().put("ok", true)) else err(500, "Could not delete")
    }

    // ---------- notifications ----------

    private fun notifications(): Response {
        val arr = JSONArray()
        for (n in Shared.notifList()) {
            arr.put(
                JSONObject().put("app", n.app).put("title", n.title)
                    .put("text", n.text).put("time", n.time)
            )
        }
        return ok(arr)
    }

    // ---------- SMS ----------

    private fun smsList(): Response {
        if (!has(Manifest.permission.READ_SMS)) return err(403, "SMS permission not granted on the phone")
        val arr = JSONArray()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "type"),
            null, null, "date DESC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < 100) {
                arr.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("address", c.getString(1) ?: "")
                        .put("body", c.getString(2) ?: "")
                        .put("date", c.getLong(3))
                        .put("type", c.getInt(4))
                )
                n++
            }
        }
        return ok(arr)
    }

    private fun smsSend(s: IHTTPSession): Response {
        if (!has(Manifest.permission.SEND_SMS)) return err(403, "SMS permission not granted on the phone")
        val j = bodyJson(s)
        val to = j.optString("to").trim()
        val text = j.optString("body")
        if (to.isEmpty() || text.isEmpty()) return err(400, "Both number and message are required")
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        sm.sendMultipartTextMessage(to, null, sm.divideMessage(text), null, null)
        return ok(JSONObject().put("ok", true))
    }

    // ---------- clipboard ----------

    private fun setClipboard(s: IHTTPSession): Response {
        val text = bodyJson(s).optString("text")
        Handler(Looper.getMainLooper()).post {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AirFree", text))
        }
        return ok(JSONObject().put("ok", true))
    }

    // ---------- screen + control ----------

    private fun screen(): Response {
        val f = Shared.frame ?: return err(404, "Screen sharing is not started on the phone")
        val r = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg", ByteArrayInputStream(f), f.size.toLong()
        )
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun gesture(s: IHTTPSession, tap: Boolean): Response {
        val c = ControlService.instance
            ?: return err(409, "Remote control is not enabled on the phone")
        val j = bodyJson(s)
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (tap) {
            val x = (j.getDouble("x") * w).toFloat()
            val y = (j.getDouble("y") * h).toFloat()
            c.swipe(x, y, x, y, 50)
        } else {
            c.swipe(
                (j.getDouble("x1") * w).toFloat(), (j.getDouble("y1") * h).toFloat(),
                (j.getDouble("x2") * w).toFloat(), (j.getDouble("y2") * h).toFloat(),
                300
            )
        }
        return ok(JSONObject().put("ok", true))
    }

    private fun key(s: IHTTPSession): Response {
        val c = ControlService.instance
            ?: return err(409, "Remote control is not enabled on the phone")
        val action = when (bodyJson(s).optString("key")) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return err(400, "Unknown key")
        }
        c.performGlobalAction(action)
        return ok(JSONObject().put("ok", true))
    }
}
