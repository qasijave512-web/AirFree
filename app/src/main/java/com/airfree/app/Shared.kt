package com.airfree.app

import android.content.Context
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

    fun pin(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("air", Context.MODE_PRIVATE)
        return prefs.getString("pin", null) ?: newPin(ctx)
    }

    fun newPin(ctx: Context): String {
        val pin = (SecureRandom().nextInt(900000) + 100000).toString()
        ctx.getSharedPreferences("air", Context.MODE_PRIVATE).edit().putString("pin", pin).apply()
        return pin
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
