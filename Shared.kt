package com.airfree.app

import android.content.Context
import android.content.SharedPreferences
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

data class NotifItem(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long
)

/** In-memory state shared between the services and the web server. */
object Shared {
    @Volatile var running = false
    @Volatile var relayConnected = false
    @Volatile var frame: ByteArray? = null
    @Volatile var sharedText = ""

    private val notifs = ArrayList<NotifItem>()

    fun addNotif(n: NotifItem) {
        synchronized(notifs) {
            notifs.removeAll { it.key == n.key }
            notifs.add(0, n)
            while (notifs.size > 100) notifs.removeAt(notifs.size - 1)
        }
    }

    fun notifList(): List<NotifItem> = synchronized(notifs) { ArrayList(notifs) }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("air", Context.MODE_PRIVATE)

    fun pin(ctx: Context): String = prefs(ctx).getString("pin", null) ?: newPin(ctx)

    fun newPin(ctx: Context): String {
        val pin = (SecureRandom().nextInt(90_000_000) + 10_000_000).toString()
        prefs(ctx).edit().putString("pin", pin).apply()
        return pin
    }

    /** Long random id that is part of the from-anywhere link. */
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("device", null)?.let { return it }
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        val id = b.joinToString("") { "%02x".format(it) }
        p.edit().putString("device", id).apply()
        return id
    }

    fun relayUrl(ctx: Context): String = prefs(ctx).getString("relay", "") ?: ""

    fun setRelayUrl(ctx: Context, raw: String) {
        var u = raw.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        prefs(ctx).edit().putString("relay", u).apply()
    }

    fun relayLink(ctx: Context): String? {
        val base = relayUrl(ctx)
        return if (base.isEmpty()) null else "$base/d/${deviceId(ctx)}/"
    }
}

fun localIp(): String {
    try {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
        for (ni in ifaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    a.hostAddress?.let { return it }
                }
            }
        }
    } catch (_: Exception) {
    }
    return "0.0.0.0"
}
