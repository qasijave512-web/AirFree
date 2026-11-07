package com.airfree.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Setup screen: start the server, grant permissions, read the PIN. */
class MainActivity : ComponentActivity() {

    private lateinit var info: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val projection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ScreenService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                ContextCompat.startForegroundService(this, i)
                toast("Screen sharing started")
                handler.postDelayed({ refresh() }, 800)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply { text = "AirFree"; textSize = 28f })
        info = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(info)

        toggle = col.btn("Start server") { toggleServer() }
        col.btn("1. Allow SMS and notifications") { requestRuntimePermissions() }
        col.btn("2. Allow access to all files") { openAllFilesAccess() }
        col.btn("3. Enable notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        col.btn("4. Enable remote control (optional)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.btn("Start screen sharing") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection.launch(mpm.createScreenCaptureIntent())
        }
        col.btn("Stop screen sharing") {
            startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_STOP))
            handler.postDelayed({ refresh() }, 500)
        }
        col.btn("Send phone clipboard to computer") { sendClipboard() }
        col.btn("Generate a new PIN") {
            Shared.newPin(this)
            refresh()
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun LinearLayout.btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this@MainActivity).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        addView(b)
        return b
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun toggleServer() {
        if (Shared.running) {
            startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(this, Intent(this, ServerService::class.java))
        }
        handler.postDelayed({ refresh() }, 700)
    }

    private fun requestRuntimePermissions() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.READ_SMS)
        list.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 30) {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissions.launch(list.toTypedArray())
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            requestRuntimePermissions()
        }
    }

    private fun sendClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            toast("Clipboard is empty")
        } else {
            Shared.sharedText = text
            toast("Sent. Open the Clipboard tab on your computer.")
        }
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    private fun refresh() {
        val ctx = this
        val filesOk = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val smsOk = checkSelfPermission(Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifOk = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(packageName)

        info.text = buildString {
            append(if (Shared.running) "Server is ON\n\n" else "Server is OFF\n\n")
            append("On your computer, open:\nhttp://${localIp()}:${ServerService.PORT}\n\n")
            append("PIN: ${Shared.pin(ctx)}\n\n")
            append("All files access: ${yesNo(filesOk)}\n")
            append("SMS access: ${yesNo(smsOk)}\n")
            append("Notification access: ${yesNo(notifOk)}\n")
            append("Remote control: ${yesNo(ControlService.instance != null)}\n")
            append("Screen sharing: ${yesNo(Shared.frame != null)}")
        }
        toggle.text = if (Shared.running) "Stop server" else "Start server"
        toggle.visibility = View.VISIBLE
    }
}
