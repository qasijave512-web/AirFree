package com.airfree.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import fi.iki.elonen.NanoHTTPD

/** Keeps the web server (and the relay connection, if configured) alive in the background. */
class ServerService : Service() {
    companion object {
        const val PORT = 8080
        const val ACTION_STOP = "com.airfree.app.STOP_SERVER"
        private const val CHANNEL = "airfree_server"
    }

    private var server: WebServer? = null
    private var tunnel: TunnelClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AirFree server", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("AirFree is running")
            .setContentText("http://${localIp()}:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (server == null) {
            val s = WebServer(applicationContext, PORT)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            Shared.running = true
        }
        reloadTunnel()
        return START_STICKY
    }

    /** (Re)connects to the relay using the address saved in the app. */
    private fun reloadTunnel() {
        tunnel?.stop()
        tunnel = null
        Shared.relayConnected = false
        val base = Shared.relayUrl(this)
        if (base.isNotEmpty()) {
            tunnel = TunnelClient(base, Shared.deviceId(this), PORT, cacheDir).also { it.start() }
        }
    }

    override fun onDestroy() {
        tunnel?.stop()
        tunnel = null
        server?.stop()
        server = null
        Shared.running = false
        super.onDestroy()
    }
}
