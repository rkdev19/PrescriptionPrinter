package com.rkstudio19.prescriptionprinter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val notificationPermissionRequestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.grantAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            requestNotificationPermissionIfNeeded()
            startPrintQueueService()
        }
    }

    // Android 13+ requires this to be granted at runtime before a visible
    // notification (including our persistent foreground-service one) will show.
    private fun requestNotificationPermissionIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)

            val imagesGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            if (!imagesGranted) permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, permissionsToRequest.toTypedArray(), notificationPermissionRequestCode
            )
        }
    }

    private fun startPrintQueueService() {
        val serviceIntent = Intent(this, PrintQueueForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        findViewById<TextView>(R.id.statusText).text =
            "Running. You can close this screen — a notification will stay active."
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Whether or not they grant it, the foreground service itself still
        // runs fine on Android 13 - only the visible notification depends on it.
    }
}
